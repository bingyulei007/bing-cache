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

/**
 * 缓存管理器接口.
 *
 * <p>定义缓存的基本操作，支持不同的缓存实现（本地、Redis 等）。</p>
 */
public interface CacheManager {

  /**
   * 从缓存中获取值.
   *
   * @param key 缓存 key
   * @return 缓存值，不存在时返回 null
   */
  Object get(String key);

  /**
   * 将值放入缓存.
   *
   * @param key           缓存 key
   * @param value         缓存值
   * @param expireSeconds 过期时间（秒），0 表示不过期
   */
  void put(String key, Object value, long expireSeconds);

  /**
   * 移除指定缓存.
   *
   * @param key 缓存 key
   */
  void evict(String key);

  /**
   * 清空所有缓存.
   */
  void clear();

  /**
   * 按前缀清除缓存.
   *
   * <p>清除所有 key 以 {@code prefix + "("} 开头的缓存条目。
   * 缓存 key 格式为 {@code prefix(args)}（见 {@code CacheKeyGenerator}），
   * 因此追加分隔符 {@code (} 可精确匹配指定 cacheName 的缓存，
   * 避免一个 cacheName 是另一个前缀时误删（如 {@code "user"} 和 {@code "userDetail"}）。</p>
   *
   * <p>用于 {@code @BingCacheEvict(allEntries=true)} 配合 cacheName 时，
   * 只清除同一 cacheName 下的缓存，而非全局清空。</p>
   *
   * @param prefix 缓存 key 前缀（即 cacheName 或 keyPrefix 的值）
   */
  void clearByPrefix(String prefix);
}
