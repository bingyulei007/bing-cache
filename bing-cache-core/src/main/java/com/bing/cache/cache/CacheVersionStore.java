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
import java.util.Set;

/**
 * 缓存版本号存储.
 *
 * <p>基于 Redis 维护各 cacheName 的版本号，用于版本对账机制。
 * 当缓存失效操作（evict/clear/clearByPrefix）发生时递增版本号，
 * 对账服务定时检查版本号变化，发现变化时清除对应前缀的 L1 缓存。</p>
 *
 * <p>Key 格式：</p>
 * <ul>
 *   <li>{@code bing-cache:version:{cacheName}} — 指定 cacheName 的版本号</li>
 *   <li>{@code bing-cache:version:__all__} — 全局版本号（allEntries 无 cacheName 时递增）</li>
 * </ul>
 */
public class CacheVersionStore {

  private static final Logger LOG = LoggerFactory.getLogger(CacheVersionStore.class);

  /** 全局版本号的 key 后缀. */
  static final String ALL_VERSION_SUFFIX = "__all__";

  private final StringRedisTemplate stringRedisTemplate;

  private final String versionKeyPrefix;

  /**
   * 构造方法.
   *
   * @param stringRedisTemplate Redis 字符串操作模板
   * @param versionKeyPrefix    版本号 key 前缀，如 "bing-cache:version:"
   */
  public CacheVersionStore(StringRedisTemplate stringRedisTemplate, String versionKeyPrefix) {
    this.stringRedisTemplate = stringRedisTemplate;
    this.versionKeyPrefix = versionKeyPrefix;
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
   * @return cacheName 集合
   */
  public Set<String> getActiveCacheNames() {
    String pattern = versionKeyPrefix + "*";
    Set<String> result = stringRedisTemplate.execute((RedisCallback<Set<String>>) connection -> {
      Set<String> names = new HashSet<>();
      ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
      var keyCommands = connection.keyCommands();
      if (keyCommands == null) {
        throw new IllegalStateException("Redis key commands are not available");
      }
      try (var cursor = keyCommands.scan(options)) {
        while (cursor.hasNext()) {
          String key = new String(cursor.next(), StandardCharsets.UTF_8);
          names.add(key.substring(versionKeyPrefix.length()));
        }
      }
      return names;
    });
    if (result == null || result.isEmpty()) {
      return Set.of();
    }
    return result;
  }
}
