package com.bing.cache.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * L1 (Caffeine) + L2 (Redis) 组合缓存管理器.
 *
 * <p>实现两级缓存策略：读取时优先命中 L1 本地缓存，未命中时查询 L2 Redis 缓存
 * 并回填 L1；写入时同时写入 L1 和 L2；失效时清理 L1 + L2 并通过 Pub/Sub
 * 通知其他实例清理其本地 L1 缓存。</p>
 */
public class CompositeCacheManager implements CacheManager {

  private static final Logger LOG = LoggerFactory.getLogger(CompositeCacheManager.class);

  private final CacheManager l1CacheManager;

  private final RedisCacheManager l2CacheManager;

  private final CacheInvalidationPublisher invalidationPublisher;

  private final CacheVersionStore versionStore;

  /**
   * 构造方法（无版本对账）.
   *
   * @param l1CacheManager        L1 本地缓存管理器
   * @param l2CacheManager        L2 Redis 缓存管理器
   * @param invalidationPublisher 缓存失效通知发布器
   */
  public CompositeCacheManager(CacheManager l1CacheManager,
      RedisCacheManager l2CacheManager,
      CacheInvalidationPublisher invalidationPublisher) {
    this(l1CacheManager, l2CacheManager, invalidationPublisher, null);
  }

  /**
   * 构造方法（支持版本对账）.
   *
   * @param l1CacheManager        L1 本地缓存管理器
   * @param l2CacheManager        L2 Redis 缓存管理器
   * @param invalidationPublisher 缓存失效通知发布器
   * @param versionStore          版本号存储（可为 null，表示不启用对账）
   */
  public CompositeCacheManager(CacheManager l1CacheManager,
      RedisCacheManager l2CacheManager,
      CacheInvalidationPublisher invalidationPublisher,
      CacheVersionStore versionStore) {
    this.l1CacheManager = l1CacheManager;
    this.l2CacheManager = l2CacheManager;
    this.invalidationPublisher = invalidationPublisher;
    this.versionStore = versionStore;
  }

  @Override
  public Object get(String key) {
    // L1 命中直接返回
    Object value = l1CacheManager.get(key);
    if (value != null) {
      LOG.debug("L1 cache hit: {}", key);
      return value;
    }

    // L1 未命中，查询 L2
    value = l2CacheManager.get(key);
    if (value != null) {
      LOG.debug("L2 cache hit, backfilling L1: {}", key);
      backfillL1(key, value);
      return value;
    }

    LOG.debug("L1+L2 cache miss: {}", key);
    return null;
  }

  @Override
  public void put(String key, Object value, long expireSeconds) {
    l1CacheManager.put(key, value, expireSeconds);
    // NullValueSentinel 实现类（BingCacheNullValue）是包私有类，Jackson 无法反序列化，只存 L1 不存 L2
    if (!(value instanceof NullValueSentinel)) {
      l2CacheManager.put(key, value, expireSeconds);
      LOG.debug("Cache put (L1+L2): {}, expireSeconds={}", key, expireSeconds);
    } else {
      LOG.debug("Cache put (L1 only, NullValueSentinel): {}, expireSeconds={}", key, expireSeconds);
    }
  }

  @Override
  public void evict(String key) {
    // 先清 L2 再清 L1，缩小 TOCTOU 窗口：
    // 如果另一个线程在 L1 evict 前 miss L1、hit L2 并回填 L1，
    // 先清 L2 可确保回填拿到的是最新数据（L2 已清，回填不会发生）
    l2CacheManager.evict(key);
    l1CacheManager.evict(key);
    invalidationPublisher.publishEvict(key);
    // 单 key evict 不写 version key：key 是完整 cache key（如 userCache(123)），
    // 若按 key 写 version，Redis 中会产生与业务 key 数量等量的 version 键，无限膨胀。
    // 跨实例失效依赖 Pub/Sub；单次 Pub/Sub 丢失可由 l1MaxTtl 兜底。
    LOG.debug("Cache evict (L2+L1+pub): {}", key);
  }

  @Override
  public void clear() {
    // 与 evict/clearByPrefix 保持一致：先清 L2 再清 L1，缩小 TOCTOU 窗口
    l2CacheManager.clear();
    l1CacheManager.clear();
    invalidationPublisher.publishClear();
    incrementAllVersion();
    LOG.debug("Cache clear (L2+L1+pub)");
  }

  @Override
  public void clearByPrefix(String prefix) {
    // 与 evict 保持一致：先清 L2 再清 L1，缩小 TOCTOU 窗口
    l2CacheManager.clearByPrefix(prefix);
    l1CacheManager.clearByPrefix(prefix);
    invalidationPublisher.publishClearByPrefix(prefix);
    incrementVersion(prefix);
    LOG.debug("Cache clear by prefix (L2+L1+pub): {}", prefix);
  }

  /**
   * 获取 L1 本地缓存管理器.
   *
   * <p>供 Pub/Sub 监听器和版本对账服务获取 L1 实例以执行本地缓存失效。</p>
   *
   * @return L1 缓存管理器
   */
  public CacheManager getL1CacheManager() {
    return l1CacheManager;
  }

  /**
   * 获取 L2 Redis 缓存管理器.
   *
   * <p>供测试和监控获取 L2 实例以验证配置和检查状态。</p>
   *
   * @return L2 Redis 缓存管理器
   */
  public RedisCacheManager getL2CacheManager() {
    return l2CacheManager;
  }

  /**
   * L2 回填 L1，处理竞态场景.
   *
   * <p>回填时通过 Redis TTL 命令获取 L2 的剩余过期时间：
   * <ul>
   *   <li>remainingTtl &gt; 0：使用剩余 TTL 回填 L1</li>
   *   <li>remainingTtl == -1：L2 永不过期，L1 也永不过期</li>
   *   <li>remainingTtl == -2 或 0：L2 中 key 已不存在或即将过期，跳过回填</li>
   * </ul>
   *
   * @param key   缓存 key
   * @param value 缓存值
   */
  private void backfillL1(String key, Object value) {
    long remainingTtl = l2CacheManager.getRemainingTtl(key);
    if (remainingTtl > 0) {
      l1CacheManager.put(key, value, remainingTtl);
    } else if (remainingTtl == -1L) {
      // L2 永不过期，L1 也永不过期
      l1CacheManager.put(key, value, 0L);
    } else {
      // remainingTtl == -2（key 不存在）或 0（即将过期）
      // 跳过回填，避免在 L1 创建永不过期的脏数据
      LOG.warn("Skip L1 backfill for key '{}': L2 remaining TTL is {} "
          + "(key may have expired or been deleted between L2 hit and TTL check)", key, remainingTtl);
    }
  }

  /**
   * 递增指定 cacheName 的版本号.
   *
   * @param cacheName 缓存名称
   */
  private void incrementVersion(String cacheName) {
    if (versionStore != null) {
      try {
        versionStore.incrementVersion(cacheName);
      } catch (Exception e) {
        LOG.warn("Failed to increment version for '{}': {}", cacheName, e.getMessage());
      }
    }
  }

  /**
   * 递增全局版本号.
   */
  private void incrementAllVersion() {
    if (versionStore != null) {
      try {
        versionStore.incrementAllVersion();
      } catch (Exception e) {
        LOG.warn("Failed to increment global version: {}", e.getMessage());
      }
    }
  }
}
