package com.bing.cache.cache;

/**
 * 缓存失效通知发布接口.
 *
 * <p>用于在缓存驱逐或清除时通知其他实例，
 * 实现可基于 Redis Pub/Sub 等机制。</p>
 */
public interface CacheInvalidationPublisher {

  /**
   * 发布单 key 驱逐通知.
   *
   * @param key 需要驱逐的缓存 key
   */
  void publishEvict(String key);

  /**
   * 发布全量清除通知.
   */
  void publishClear();

  /**
   * 发布按前缀清除通知.
   *
   * @param prefix 需要清除的缓存 key 前缀
   */
  void publishClearByPrefix(String prefix);
}
