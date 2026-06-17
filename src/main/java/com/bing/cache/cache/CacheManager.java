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
   * <p>清除所有 key 以指定前缀开头的缓存条目。
   * 用于 {@code @BingCacheEvict(allEntries=true)} 配合 cacheName 时，
   * 只清除同一 cacheName 下的缓存，而非全局清空。</p>
   *
   * @param prefix 缓存 key 前缀（即 cacheName 或 keyPrefix 的值）
   */
  void clearByPrefix(String prefix);
}
