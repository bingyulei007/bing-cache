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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 缓存版本号存储.
 *
 * <p>基于 Redis 维护各 cacheName 的版本号，用于版本对账机制。
 * 当缓存失效操作（evict/clear/clearByPrefix）发生时递增版本号，
 * 对账服务定时检查版本号变化，发现变化时清除对应前缀的 L1 缓存。</p>
 *
 * <p>Key 格式（使用独立 {@code __version__} 前缀，与业务缓存 key 隔离，
 * 避免 {@code RedisCacheManager.clear()} 用 {@code keyPrefix + "*"} 扫描时误删）：</p>
 * <ul>
 *   <li>{@code bing-cache:__version__:{cacheName}} — 指定 cacheName 的版本号</li>
 *   <li>{@code bing-cache:__version__:__all__} — 全局版本号（allEntries 无 cacheName 时递增）</li>
 *   <li>{@code bing-cache:__version__:__group__:groupName} — 指定 group 的版本号</li>
 * </ul>
 *
 * <p><b>保留前缀约束：</b>{@code __all__} 和 {@code __group__:}
 * 是内部保留的 cacheName 前缀，业务侧不应使用以 {@code __group__:}
 * 开头或等于 {@code __all__} 的 cacheName，否则版本对账失效。</p>
 */
public class CacheVersionStore {

  private static final Logger LOG = LoggerFactory.getLogger(CacheVersionStore.class);

  /** 全局版本号的 key 后缀. */
  static final String ALL_VERSION_SUFFIX = "__all__";

  /** Group 版本号 key 的前缀分隔符，与 {@link #ALL_VERSION_SUFFIX} 风格一致. */
  static final String GROUP_VERSION_PREFIX = "__group__:";

  private final StringRedisTemplate stringRedisTemplate;

  private final String versionKeyPrefix;

  /**
   * 构造方法.
   *
   * @param stringRedisTemplate Redis 字符串操作模板
   * @param versionKeyPrefix    版本号 key 前缀，如 "bing-cache:__version__:"
   */
  public CacheVersionStore(StringRedisTemplate stringRedisTemplate, String versionKeyPrefix) {
    this.stringRedisTemplate = Objects.requireNonNull(stringRedisTemplate, "stringRedisTemplate cannot be null");
    this.versionKeyPrefix = Objects.requireNonNull(versionKeyPrefix, "versionKeyPrefix cannot be null");
  }

  /**
   * 递增指定 cacheName 的版本号.
   *
   * @param cacheName 缓存名称
   * @return 递增后的版本号
   */
  public long incrementVersion(String cacheName) {
    String key = versionKeyPrefix + cacheName;
    Long version = stringRedisTemplate.opsForValue().increment(key);
    LOG.debug("Incremented version for '{}': {}", cacheName, version);
    return version != null ? version : 0L;
  }

  /**
   * 递增全局版本号.
   *
   * @return 递增后的版本号
   */
  public long incrementAllVersion() {
    return incrementVersion(ALL_VERSION_SUFFIX);
  }

  /**
   * 递增指定 group 的版本号.
   *
   * <p>与 {@link #incrementVersion(String)} 和 {@link #incrementAllVersion()} 一致，
   * 版本号用于版本对账机制，当对账服务检测到版本号变化时清除对应 group 的 L1 缓存。</p>
   *
   * @param group 缓存分组名称
   * @return 递增后的版本号
   */
  public long incrementGroupVersion(String group) {
    return incrementVersion(GROUP_VERSION_PREFIX + group);
  }

  /**
   * 获取指定 group 的当前版本号.
   *
   * @param group 缓存分组名称
   * @return 版本号，key 不存在时返回 0
   */
  public long getGroupVersion(String group) {
    return getVersion(GROUP_VERSION_PREFIX + group);
  }

  /**
   * 获取指定 cacheName 的当前版本号.
   *
   * @param cacheName 缓存名称
   * @return 版本号，key 不存在时返回 0
   */
  public long getVersion(String cacheName) {
    String key = versionKeyPrefix + cacheName;
    String value = stringRedisTemplate.opsForValue().get(key);
    if (value != null) {
      try {
        return Long.parseLong(value);
      } catch (NumberFormatException e) {
        LOG.warn("Invalid version value for '{}': {}", cacheName, value);
        return 0L;
      }
    }
    return 0L;
  }

  /**
   * 获取全局版本号.
   *
   * @return 全局版本号，key 不存在时返回 0
   */
  public long getAllVersion() {
    return getVersion(ALL_VERSION_SUFFIX);
  }

  /**
   * 获取所有已注册的 cacheName.
   *
   * <p>通过 SCAN 命令扫描版本号 key 前缀，提取 cacheName 部分。
   * 使用 SCAN 替代 KEYS 命令，避免在 key 数量较大时阻塞 Redis。</p>
   *
   * @return Optional.empty() 表示扫描能力暂不可用；Optional.of(names) 表示扫描成功，names 可能为空集
   */
  public Optional<Set<String>> getActiveCacheNames() {
    String pattern = versionKeyPrefix + "*";
    Optional<Set<String>> result = stringRedisTemplate.execute((RedisCallback<Optional<Set<String>>>) connection -> {
      Set<String> names = new HashSet<>();
      ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
      var keyCommands = connection.keyCommands();
      if (keyCommands == null) {
        // keyCommands 为 null（集群模式、连接切换等瞬时状态），
        // 用 Optional.empty() 表示本轮扫描不可用，避免与真实空集混淆。
        LOG.warn("Redis key commands are not available, skipping this reconciliation cycle");
        return Optional.empty();
      }
      try (var cursor = keyCommands.scan(options)) {
        while (cursor.hasNext()) {
          String key = new String(cursor.next(), StandardCharsets.UTF_8);
          String name = key.substring(versionKeyPrefix.length());
          if (ALL_VERSION_SUFFIX.equals(name) || name.startsWith(GROUP_VERSION_PREFIX)) {
            continue;
          }
          names.add(name);
        }
      }
      return Optional.of(names);
    });
    return result != null ? result : Optional.empty();
  }

  /**
   * 获取所有活跃的 group 名称.
   *
   * @return Optional.empty() 表示扫描能力暂不可用；Optional.of(groups) 表示扫描成功，groups 可能为空集
   */
  public Optional<Set<String>> getActiveGroups() {
    String pattern = versionKeyPrefix + GROUP_VERSION_PREFIX + "*";
    Optional<Set<String>> result = stringRedisTemplate.execute(
        (RedisCallback<Optional<Set<String>>>) connection -> {
        Set<String> groups = new HashSet<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
        var keyCommands = connection.keyCommands();
        if (keyCommands == null) {
          LOG.warn("Redis key commands are not available, skipping group scan");
          return Optional.empty();
        }
        try (var cursor = keyCommands.scan(options)) {
          while (cursor.hasNext()) {
            String key = new String(cursor.next(), StandardCharsets.UTF_8);
            String groupName = key.substring(
                (versionKeyPrefix + GROUP_VERSION_PREFIX).length());
            groups.add(groupName);
          }
        }
        return Optional.of(groups);
    });
    return result != null ? result : Optional.empty();
  }
}
