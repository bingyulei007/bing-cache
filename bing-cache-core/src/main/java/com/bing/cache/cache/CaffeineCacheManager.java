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
 */
public class CaffeineCacheManager implements CacheManager {

  private final long maxSize;

  private final long l1MaxTtlSeconds;

  private final Cache<String, CacheEntry> cache;

  /**
   * 默认构造方法，使用最大缓存条目数 1000，不限制 L1 最大存活时间.
   */
  public CaffeineCacheManager() {
    this(1000L, 0L);
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
    CacheEntry entry = cache.getIfPresent(key);
    if (entry == null) {
      return null;
    }
    return entry.value();
  }

  @Override
  public void put(String key, Object value, long expireSeconds) {
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
    cache.put(key, new CacheEntry(value, expireNanos));
  }

  @Override
  public void evict(String key) {
    cache.invalidate(key);
  }

  @Override
  public void clear() {
    cache.invalidateAll();
  }

  @Override
  public void clearByPrefix(String prefix) {
    // 缓存 key 格式为 prefix(args)，追加 "(" 精确匹配 cacheName，
    // 避免一个 cacheName 是另一个前缀时误删（如 clearByPrefix("user") 误删 "userDetail" 的 key）。
    String matchPrefix = prefix + "(";
    cache.asMap().keySet().removeIf(key -> key.startsWith(matchPrefix));
  }

  @Override
  public void clearByGroup(String group) {
    // 匹配 "group:" 开头的 L1 key
    // 缓存 key 格式为 group:prefix(args)，匹配 group: 命名空间前缀
    String matchPrefix = group + ":";
    cache.asMap().keySet().removeIf(key -> key.startsWith(matchPrefix));
  }

  /**
   * 获取当前缓存中所有的 key（用于对账等场景）.
   *
   * @return key 集合
   */
  public Set<String> keys() {
    return cache.asMap().keySet();
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
