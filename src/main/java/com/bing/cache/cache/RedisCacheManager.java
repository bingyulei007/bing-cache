package com.bing.cache.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 Redis 的缓存管理器实现.
 *
 * <p>使用 Redis 作为 L2 分布式缓存，支持按 key 前缀命名空间隔离，
 * TTL 直接映射 Redis 的过期机制。</p>
 *
 * <p>当 Redis 连续操作失败超过阈值时，会输出 WARN 级别降级日志；
 * 当操作恢复正常时，会输出 INFO 级别恢复日志，并触发恢复回调（如清空 L1 脏数据）。</p>
 */
public class RedisCacheManager implements CacheManager {

  private static final Logger LOG = LoggerFactory.getLogger(RedisCacheManager.class);

  /** 连续失败次数达到此阈值时输出降级警告. */
  private static final int DEGRADATION_THRESHOLD = 3;

  private final RedisTemplate<String, Object> redisTemplate;

  private final String keyPrefix;

  private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

  private volatile boolean degradationWarned = false;

  private volatile Runnable recoveryCallback;

  /**
   * 构造方法.
   *
   * @param redisTemplate Redis 操作模板（key 使用 StringRedisSerializer，
   *                      value 使用 GenericJackson2JsonRedisSerializer）
   * @param keyPrefix     Redis key 前缀，如 "bing-cache:"
   */
  public RedisCacheManager(RedisTemplate<String, Object> redisTemplate,
      String keyPrefix) {
    this.redisTemplate = redisTemplate;
    this.keyPrefix = keyPrefix;
  }

  @Override
  public Object get(String key) {
    String redisKey = redisKey(key);
    try {
      Object value = redisTemplate.opsForValue().get(redisKey);
      if (value != null) {
        LOG.debug("Redis cache hit: {}", redisKey);
      }
      recordSuccess();
      return value;
    } catch (Exception e) {
      recordFailure("get", redisKey, e);
      return null;
    }
  }

  @Override
  public void put(String key, Object value, long expireSeconds) {
    String redisKey = redisKey(key);
    try {
      if (expireSeconds > 0) {
        redisTemplate.opsForValue().set(redisKey, value, expireSeconds, TimeUnit.SECONDS);
      } else {
        redisTemplate.opsForValue().set(redisKey, value);
      }
      LOG.debug("Redis cache put: {}, expireSeconds={}", redisKey, expireSeconds);
      recordSuccess();
    } catch (Exception e) {
      recordFailure("put", redisKey, e);
    }
  }

  @Override
  public void evict(String key) {
    String redisKey = redisKey(key);
    try {
      redisTemplate.delete(redisKey);
      LOG.debug("Redis cache evict: {}", redisKey);
      recordSuccess();
    } catch (Exception e) {
      recordFailure("evict", redisKey, e);
    }
  }

  @Override
  public void clear() {
    try {
      String pattern = keyPrefix + "*";
      List<String> keys = scanKeys(pattern);
      if (keys != null && !keys.isEmpty()) {
        redisTemplate.delete(keys);
        LOG.debug("Redis cache clear: {} keys deleted", keys.size());
      }
      recordSuccess();
    } catch (Exception e) {
      recordFailure("clear", keyPrefix, e);
    }
  }

  @Override
  public void clearByPrefix(String prefix) {
    try {
      String pattern = keyPrefix + prefix + "*";
      List<String> keys = scanKeys(pattern);
      if (keys != null && !keys.isEmpty()) {
        redisTemplate.delete(keys);
        LOG.debug("Redis cache clear by prefix {}: {} keys deleted", prefix, keys.size());
      }
      recordSuccess();
    } catch (Exception e) {
      recordFailure("clearByPrefix", keyPrefix + prefix, e);
    }
  }

  private String redisKey(String key) {
    return keyPrefix + key;
  }

  /**
   * 使用 SCAN 命令扫描匹配指定 pattern 的 key.
   *
   * @param pattern 匹配模式
   * @return 匹配的 key 列表
   */
  private List<String> scanKeys(String pattern) {
    return redisTemplate.execute((RedisCallback<List<String>>) connection -> {
      List<String> result = new ArrayList<>();
      ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
      try (var cursor = connection.keyCommands().scan(options)) {
        while (cursor.hasNext()) {
          result.add(new String(cursor.next(), StandardCharsets.UTF_8));
        }
      }
      return result;
    });
  }

  /**
   * 获取 Redis key 的剩余过期时间.
   *
   * <p>用于 L1 回填时携带剩余 TTL，避免回填后的 L1 条目
   * 在 L2 过期后仍长时间驻留本地缓存。</p>
   *
   * @param key 缓存 key（不含 Redis 前缀）
   * @return 剩余过期秒数；-1 表示永不过期；-2 表示 key 不存在；0 表示已过期
   */
  public long getRemainingTtl(String key) {
    String redisKey = redisKey(key);
    try {
      Long ttl = redisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
      if (ttl != null) {
        return ttl;
      }
      return -2L;
    } catch (Exception e) {
      LOG.error("Failed to get TTL from Redis: {}", redisKey, e);
      return -2L;
    }
  }

  /**
   * 设置 Redis 恢复时的回调.
   *
   * <p>当 Redis 从降级状态恢复时，会触发此回调。
   * 典型用途：清空 L1 缓存中可能在降级期间写入的脏数据。</p>
   *
   * @param callback 恢复回调
   */
  public void setRecoveryCallback(Runnable callback) {
    this.recoveryCallback = callback;
  }

  /**
   * 记录操作成功，重置失败计数.
   *
   * <p>如果之前处于降级状态，输出 INFO 级别恢复日志，
   * 并触发恢复回调。</p>
   *
   * <p>恢复判断通过 synchronized 保证原子性：多个线程并发成功时，
   * 仅一个线程能将 {@code degradationWarned} 从 true 翻转为 false 并触发回调，
   * 避免回调被重复触发。回调在锁外执行，避免持锁过久。</p>
   */
  private void recordSuccess() {
    consecutiveFailures.set(0);
    boolean recovered = false;
    synchronized (this) {
      if (degradationWarned) {
        degradationWarned = false;
        recovered = true;
      }
    }
    if (recovered) {
      LOG.info("Bing Cache: Redis L2 cache has recovered from degradation");
      triggerRecoveryCallback();
    }
  }

  /**
   * 触发恢复回调.
   */
  private void triggerRecoveryCallback() {
    Runnable callback = this.recoveryCallback;
    if (callback != null) {
      try {
        callback.run();
      } catch (Exception e) {
        LOG.error("Recovery callback execution failed", e);
      }
    }
  }

  /**
   * 记录操作失败，递增失败计数.
   *
   * <p>当连续失败次数达到阈值时，输出 WARN 级别降级日志，
   * 提示运维 Redis 不可用，缓存已降级为纯 L1 模式。</p>
   *
   * @param operation 操作名称
   * @param key       操作的 key
   * @param e         异常
   */
  private void recordFailure(String operation, String key, Exception e) {
    LOG.error("Failed to {} from Redis: {}", operation, key, e);
    int failures = consecutiveFailures.incrementAndGet();
    if (failures >= DEGRADATION_THRESHOLD && !degradationWarned) {
      degradationWarned = true;
      LOG.warn("Bing Cache: Redis L2 cache has failed {} consecutive times, "
          + "degraded to L1-only mode. Check Redis connectivity.",
          failures);
    }
  }
}
