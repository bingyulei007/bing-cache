/*
 * Copyright 2026 Bing Cache contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bing.cache.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Caffeine 的缓存管理器实现.
 *
 * <p>使用单个 Caffeine Cache 实例 + per-entry Expiry 策略，
 * 每个缓存条目携带独立的过期时间，避免按 TTL 分组导致的多实例膨胀问题。</p>
 *
 * <p>支持 L1 最大存活时间限制（l1MaxTtl），当配置后，
 * 所有 L1 条目的过期时间不超过该值，作为 Pub/Sub 丢失或 Redis 不可用时的兜底保障。</p>
 *
 * <p>支持按前缀独立限容：当 {@code @BingCache(maxSize > 0)} 时，
 * 为该注解对应的缓存前缀创建独立的 Caffeine 实例，容量单独控制，
 * 避免高基数方法的条目挤满全局池子驱逐其他热点缓存。</p>
 */
public class CaffeineCacheManager implements CacheManager {

  private final long maxSize;

  private final long l1MaxTtlSeconds;

  /** 全局共享 Caffeine 实例（maxSize=0 或未注册前缀的条目使用）. */
  private final Cache<String, CacheEntry> cache;

  /**
   * 按前缀独立限容的缓存实例，key 为缓存前缀（cacheName 或 keyPrefix 或 group:cacheName）.
   *
   * <p>注册表有界：仅当注解声明 {@code maxSize > 0} 且首次 put 时才懒创建，
   * 数量等于应用中标注了 maxSize 的方法数，天然有界。</p>
   */
  private final Map<String, Cache<String, CacheEntry>> sizedCaches = new ConcurrentHashMap<>();

  /**
   * 默认构造方法，使用最大缓存条目数 5000，不限制 L1 最大存活时间.
   */
  public CaffeineCacheManager() {
    this(5000L, 0L);
  }

  /**
   * 构造方法，指定最大缓存条目数.
   *
   * @param maxSize 每个缓存实例的最大条目数
   */
  public CaffeineCacheManager(long maxSize) {
    this(maxSize, 0L);
  }

  /**
   * 构造方法，指定最大缓存条目数和 L1 最大存活时间.
   *
   * @param maxSize         最大缓存条目数
   * @param l1MaxTtlSeconds L1 最大存活秒数，0 表示不限制
   */
  public CaffeineCacheManager(long maxSize, long l1MaxTtlSeconds) {
    this.maxSize = maxSize;
    this.l1MaxTtlSeconds = l1MaxTtlSeconds;
    this.cache = Caffeine.newBuilder()
        .maximumSize(maxSize)
        .expireAfter(new CacheEntryExpiry())
        .build();
  }

  @Override
  public Object get(String key) {
    CacheEntry entry = resolveCacheForRead(key).getIfPresent(key);
    if (entry == null) {
      return null;
    }
    return entry.value();
  }

  @Override
  public void put(String key, Object value, long expireSeconds) {
    put(key, value, expireSeconds, 0);
  }

  @Override
  public void put(String key, Object value, long expireSeconds, long maxSize) {
    // 禁止缓存 null 值：get() 返回 null 时需明确表示"未缓存"，
    // 若允许缓存 null 会与"未缓存"状态混淆。
    // CacheAspect 通过 BingCacheNullValue 占位符规避了 null 缓存需求。
    if (value == null) {
      throw new IllegalArgumentException(
          "Cache value must not be null. Use a NullValueSentinel placeholder "
              + "(e.g. BingCacheNullValue.INSTANCE) when caching a null result, "
              + "or set @BingCache(cacheNullValue = false) to skip caching when the result is null.");
    }
    long effectiveExpire = expireSeconds;
    // 应用 L1 最大存活时间限制
    if (l1MaxTtlSeconds > 0 && effectiveExpire > 0) {
      effectiveExpire = Math.min(effectiveExpire, l1MaxTtlSeconds);
    } else if (l1MaxTtlSeconds > 0 && effectiveExpire <= 0) {
      // 原本永不过期，但设置了 maxTtl 限制
      effectiveExpire = l1MaxTtlSeconds;
    }
    long expireNanos = effectiveExpire > 0
        ? TimeUnit.SECONDS.toNanos(effectiveExpire)
        : Long.MAX_VALUE;
    String prefix = extractPrefix(key);
    Cache<String, CacheEntry> target = resolveCacheForWrite(prefix, maxSize);
    target.put(key, new CacheEntry(value, expireNanos));
  }

  @Override
  public void evict(String key) {
    // 双侧兜底：正常路由下条目只在一侧，但独立缓存创建瞬间存在竞态
    // （回填先进了全局缓存），双侧 invalidate 兜底清理残留。
    String prefix = extractPrefix(key);
    cache.invalidate(key);
    Cache<String, CacheEntry> sized = sizedCaches.get(prefix);
    if (sized != null) {
      sized.invalidate(key);
    }
  }

  @Override
  public void clear() {
    cache.invalidateAll();
    // 清空所有独立缓存的内容，但保留注册表——注册表有界且稳定，
    // 保证清空后路由依然指向正确的实例。
    for (Cache<String, CacheEntry> sized : sizedCaches.values()) {
      sized.invalidateAll();
    }
  }

  @Override
  public void clearByPrefix(String prefix) {
    // 缓存 key 格式为 prefix(args)，追加 "(" 精确匹配 cacheName，
    // 避免一个 cacheName 是另一个前缀时误删（如 clearByPrefix("user") 误删 "userDetail" 的 key）。
    String matchPrefix = prefix + "(";
    // ⚠️ 并发残留窗口：cache.asMap() 返回 ConcurrentMap，其 keySet().removeIf 基于
    // weakly consistent 迭代器，本轮 remove 期间并发 put 写入的新 key 可能不被迭代器看到。
    // 这些残留 key 既不会被本轮 removeIf 清除，也不会被版本对账清除（本实例已将版本号
    // 记录为最新），只能靠 l1-max-ttl 自然过期兜底。这是 Caffeine 无锁并发设计的固有取舍。
    cache.asMap().keySet().removeIf(key -> key.startsWith(matchPrefix));
    Cache<String, CacheEntry> sized = sizedCaches.get(prefix);
    if (sized != null) {
      sized.invalidateAll();
    }
  }

  @Override
  public void clearByGroup(String group) {
    // 匹配 "group:" 开头的 L1 key
    // 缓存 key 格式为 group:prefix(args)，匹配 group: 命名空间前缀
    String matchPrefix = group + ":";
    // ⚠️ 并发残留窗口：同 clearByPrefix，weakly consistent 迭代器可能漏掉并发 put 的新 key，
    // 残留 key 只能靠 l1-max-ttl 兜底。
    cache.asMap().keySet().removeIf(key -> key.startsWith(matchPrefix));
    for (Map.Entry<String, Cache<String, CacheEntry>> entry : sizedCaches.entrySet()) {
      if (entry.getKey().startsWith(matchPrefix)) {
        entry.getValue().invalidateAll();
      }
    }
  }

  /**
   * 强制触发 Caffeine 维护任务（测试用）.
   *
   * <p>Caffeine 的容量淘汰依赖维护任务延迟执行（写缓冲攒批处理），
   * 少量 put 不触发即时淘汰。测试写入后调用此方法可强制触发维护，
   * 确保容量上限断言具有确定性，避免 flaky test。</p>
   */
  void cleanUp() {
    cache.cleanUp();
    for (Cache<String, CacheEntry> sized : sizedCaches.values()) {
      sized.cleanUp();
    }
  }

  /**
   * 获取当前缓存中所有的 key（用于对账等场景）.
   *
   * <p>合并全局缓存与所有独立限容缓存的 key，返回快照副本
   * （非 live view），避免迭代期间并发写入抛出
   * {@code ConcurrentModificationException}。</p>
   *
   * @return key 集合
   */
  public Set<String> keys() {
    Set<String> keys = new HashSet<>(cache.asMap().keySet());
    for (Cache<String, CacheEntry> sized : sizedCaches.values()) {
      keys.addAll(sized.asMap().keySet());
    }
    return keys;
  }

  /**
   * 获取最大缓存条目数.
   *
   * @return 最大缓存条目数
   */
  public long getMaxSize() {
    return maxSize;
  }

  /**
   * 获取 L1 最大存活秒数.
   *
   * @return L1 最大存活秒数，0 表示不限制
   */
  public long getL1MaxTtlSeconds() {
    return l1MaxTtlSeconds;
  }

  /**
   * 从 key 中提取缓存前缀.
   *
   * <p>缓存 key 格式为 {@code prefix(args)}，其中 args 部分为 {@code Sg[...]}（单值）、
   * {@code [...]}（多值）或空（无参）。前缀不含 args 部分。
   * 从右向左查找 args 分隔符 {@code (}，确保找到的是 args 分隔符而非 prefix
   * 内部的 {@code (}（如默认前缀 {@code className.methodName(paramTypes)} 含 {@code (}）。</p>
   *
   * @param key 缓存 key
   * @return 缓存前缀，若无法解析则返回 key 本身
   */
  private String extractPrefix(String key) {
    // 从右向左查找 args 分隔符，避免默认前缀中的 "(" 干扰
    // 优先级：Sg... > [...] > ()
    int idx = key.lastIndexOf("(Sg[");
    if (idx >= 0) {
      return key.substring(0, idx);
    }
    idx = key.lastIndexOf("([");
    if (idx >= 0) {
      return key.substring(0, idx);
    }
    idx = key.lastIndexOf("()");
    if (idx >= 0) {
      return key.substring(0, idx);
    }
    // 未找到标准分隔符，使用整个 key 作为 prefix（兜底，如截断 key 或非标准 key）
    return key;
  }

  /**
   * 读取路由：按前缀查注册表，已注册走独立缓存，否则走全局缓存.
   *
   * @param key 缓存 key
   * @return 对应的 Caffeine 实例
   */
  private Cache<String, CacheEntry> resolveCacheForRead(String key) {
    String prefix = extractPrefix(key);
    Cache<String, CacheEntry> sized = sizedCaches.get(prefix);
    return sized != null ? sized : cache;
  }

  /**
   * 写入路由：已注册前缀 → 独立缓存；首次携带 maxSize > 0 → 懒创建独立缓存；
   * 否则 → 全局缓存.
   *
   * <p>与读取路由共用同一套规则，杜绝读写分裂。</p>
   *
   * @param prefix  缓存前缀
   * @param maxSize 注解声明的 maxSize，0 表示不限制
   * @return 对应的 Caffeine 实例
   */
  private Cache<String, CacheEntry> resolveCacheForWrite(String prefix, long maxSize) {
    Cache<String, CacheEntry> sized = sizedCaches.get(prefix);
    if (sized != null) {
      return sized;
    }
    if (maxSize > 0) {
      return sizedCaches.computeIfAbsent(prefix,
          p -> Caffeine.newBuilder()
              .maximumSize(maxSize)
              .expireAfter(new CacheEntryExpiry())
              .build());
    }
    return cache;
  }

  /**
   * 缓存条目，携带值和过期纳秒数.
   *
   * @param value      缓存值
   * @param expireNanos 过期纳秒数（相对于创建时间），Long.MAX_VALUE 表示永不过期
   */
  record CacheEntry(Object value, long expireNanos) {
  }

  /**
   * Per-entry 过期策略.
   *
   * <p>每个 CacheEntry 携带自己的过期纳秒数，
   * Caffeine 在读取或写入时根据此值计算条目是否过期。</p>
   */
  static final class CacheEntryExpiry implements Expiry<String, CacheEntry> {

    @Override
    public long expireAfterCreate(String key, CacheEntry entry, long currentTime) {
      return entry.expireNanos();
    }

    @Override
    public long expireAfterUpdate(String key, CacheEntry entry, long currentTime,
        long currentDuration) {
      return entry.expireNanos();
    }

    @Override
    public long expireAfterRead(String key, CacheEntry entry, long currentTime,
        long currentDuration) {
      return currentDuration;
    }
  }
}
