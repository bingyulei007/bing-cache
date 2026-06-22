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

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

  /** 降级状态下连续成功次数达到此阈值时恢复，防止 Redis flapping 反复触发恢复回调. */
  private static final int RECOVERY_THRESHOLD = 3;

  /** SCAN 命令每次迭代返回的 key 数量默认值. */
  private static final long DEFAULT_SCAN_COUNT = 1000L;

  /** Redis 批量删除的批次大小默认值. */
  private static final long DEFAULT_DELETE_BATCH_SIZE = 500L;

  /** 是否使用 UNLINK 命令（非阻塞删除）代替 DELETE 的默认值. */
  private static final boolean DEFAULT_USE_UNLINK = true;

  /** 失败日志限流间隔（秒）默认值. */
  private static final long DEFAULT_FAILURE_LOG_INTERVAL_SECONDS = 30L;

  private final RedisTemplate<String, Object> redisTemplate;

  private final String keyPrefix;

  private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

  /** 降级状态下连续成功操作计数器，达到 {@link #RECOVERY_THRESHOLD} 才恢复. */
  private final AtomicInteger consecutiveSuccesses = new AtomicInteger(0);

  private volatile boolean degradationWarned = false;

  private volatile Runnable recoveryCallback;

  private final long scanCount;

  private final long deleteBatchSize;

  private final boolean useUnlink;

  private final long failureLogIntervalSeconds;

  /** 降级状态下上次输出失败摘要日志的时间戳（纳秒）. */
  private volatile long lastDegradedFailureLogNanos;

  /**
   * 构造方法（兼容旧版本，使用默认性能参数）.
   *
   * @param redisTemplate Redis 操作模板（key 使用 StringRedisSerializer，
   *                      value 使用 GenericJackson2JsonRedisSerializer）
   * @param keyPrefix     Redis key 前缀，如 "bing-cache:"
   */
  public RedisCacheManager(RedisTemplate<String, Object> redisTemplate,
      String keyPrefix) {
    this(redisTemplate, keyPrefix,
        DEFAULT_SCAN_COUNT,
        DEFAULT_DELETE_BATCH_SIZE,
        DEFAULT_USE_UNLINK,
        DEFAULT_FAILURE_LOG_INTERVAL_SECONDS);
  }

  /**
   * 构造方法（支持性能参数配置）.
   *
   * @param redisTemplate           Redis 操作模板
   * @param keyPrefix               Redis key 前缀
   * @param scanCount               SCAN 命令每次迭代的 key 数量
   * @param deleteBatchSize         批量删除的批次大小
   * @param useUnlink               是否使用 UNLINK 替代 DELETE 执行异步删除
   * @param failureLogIntervalSeconds 失败日志的最小输出间隔（秒），必须大于 0
   */
  public RedisCacheManager(RedisTemplate<String, Object> redisTemplate,
      String keyPrefix,
      long scanCount,
      long deleteBatchSize,
      boolean useUnlink,
      long failureLogIntervalSeconds) {
    this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate cannot be null");
    this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix cannot be null");
    if (scanCount < 1 || scanCount > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("scanCount must be between 1 and "
          + Integer.MAX_VALUE);
    }
    if (deleteBatchSize < 1 || deleteBatchSize > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("deleteBatchSize must be between 1 and "
          + Integer.MAX_VALUE);
    }
    if (failureLogIntervalSeconds < 1) {
      throw new IllegalArgumentException("failureLogIntervalSeconds must be greater than 0");
    }
    this.scanCount = scanCount;
    this.deleteBatchSize = deleteBatchSize;
    this.useUnlink = useUnlink;
    this.failureLogIntervalSeconds = failureLogIntervalSeconds;
  }

  /**
   * 获取 SCAN 命令每次迭代的 key 数量.
   *
   * @return scanCount
   */
  public long getScanCount() {
    return scanCount;
  }

  /**
   * 获取批量删除的批次大小.
   *
   * @return deleteBatchSize
   */
  public long getDeleteBatchSize() {
    return deleteBatchSize;
  }

  /**
   * 是否使用 UNLINK 替代 DELETE 执行异步删除.
   *
   * @return true 表示使用 UNLINK，false 表示使用 DELETE
   */
  public boolean isUseUnlink() {
    return useUnlink;
  }

  /**
   * 获取失败日志的最小输出间隔（秒）.
   *
   * @return failureLogIntervalSeconds
   */
  public long getFailureLogInterval() {
    return failureLogIntervalSeconds;
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
      // clear() 全清本命名空间下所有业务缓存 key，但需排除版本号 key
      //（前缀 bing-cache:__version__:），避免破坏 CacheReconciliationService 的对账状态。
      String excludePrefix = keyPrefix + "__version__:";
      long deleted = scanAndDeleteInBatches(pattern, null, excludePrefix, useUnlink);
      LOG.debug("Redis cache clear: {} keys deleted", deleted);
      recordSuccess();
    } catch (Exception e) {
      recordFailure("clear", keyPrefix, e);
    }
  }

  @Override
  public void clearByPrefix(String prefix) {
    try {
      // 字面前缀：缓存 key 格式为 prefix(args)，追加分隔符 "(" 精确匹配 cacheName，
      // 避免一个 cacheName 是另一个前缀时的误删（如 "user" 和 "userDetail"）。
      // 同时用于 SCAN 后的 Java 端二次过滤，避免 prefix 含 glob 元字符（* ? [ ]）时误匹配。
      String literalPrefix = keyPrefix + prefix + "(";
      String pattern = literalPrefix + "*";
      long deleted = scanAndDeleteInBatches(pattern, literalPrefix, null, useUnlink);
      LOG.debug("Redis cache clear by prefix {}: {} keys deleted", prefix, deleted);
      recordSuccess();
    } catch (Exception e) {
      recordFailure("clearByPrefix", keyPrefix + prefix, e);
    }
  }

  /**
   * 批量删除结果记录.
   *
   * @param deleted       本批次删除的 key 数量
   * @param keepTryingUnlink 是否继续尝试使用 UNLINK（false 表示已经失败，应回退到 DEL）
   */
  private record DeleteBatchResult(long deleted, boolean keepTryingUnlink) {}

  /**
   * 使用 SCAN 扫描并分批次删除 Redis key.
   *
   * <p>扫描过程中累积 key 到指定批次大小，达到阈值后立即删除，
   * 避免一次性加载所有 key 到内存。优先使用 UNLINK（非阻塞删除），
   * 若失败则回退到 DEL 并对后续所有批次继续使用 DEL。</p>
   *
   * <p><b>字面前缀二次过滤</b>：当 {@code literalPrefix} 非 null 时，SCAN 返回的每个
   * key 会先用 {@code String.startsWith(literalPrefix)} 在 Java 端二次校验，确保
   * 只有字面前缀匹配的 key 被删除。{@code literalPrefix} 已包含分隔符 {@code (}，
   * 因此 {@code clearByPrefix("user")} 的字面前缀为 {@code "bing-cache:user("}，
   * 不会误匹配 {@code "bing-cache:userDetail("} 开头的 key。
   * {@code literalPrefix} 为 null 时（如 {@link #clear()} 全清场景）跳过二次过滤。</p>
   *
   * <p><b>排除前缀</b>：当 {@code excludePrefix} 非 null 时，以该前缀开头的 key 会被跳过。
   * 用于 {@link #clear()} 全清场景排除版本号 key（{@code bing-cache:__version__:}），
   * 避免破坏 {@link CacheReconciliationService} 的对账状态。</p>
   *
   * @param pattern        Redis SCAN MATCH 模式（粗筛）
   * @param literalPrefix  字面前缀（null 表示不过滤）
   * @param excludePrefix  排除前缀（null 表示不排除）
   * @param useUnlink      是否优先尝试 UNLINK
   * @return 实际删除的 key 总数
   */
  private long scanAndDeleteInBatches(String pattern, String literalPrefix,
      String excludePrefix, boolean useUnlink) {
    return redisTemplate.execute((RedisCallback<Long>) connection -> {
      long totalDeleted = 0;
      List<byte[]> batch = new ArrayList<>();
      boolean keepTryingUnlink = useUnlink;

      ScanOptions options = ScanOptions.scanOptions()
          .match(pattern)
          .count(scanCount)
          .build();
      RedisKeyCommands keyCommands = keyCommands(connection);

      try (var cursor = keyCommands.scan(options)) {
        while (cursor.hasNext()) {
          byte[] keyBytes = cursor.next();
          String key = new String(keyBytes, StandardCharsets.UTF_8);

          // 排除前缀过滤：跳过版本号等内部 key（clear() 全清场景使用）
          if (excludePrefix != null && key.startsWith(excludePrefix)) {
            continue;
          }

          // 字面前缀二次过滤：跳过 glob 误匹配的 key
          if (literalPrefix != null && !key.startsWith(literalPrefix)) {
            continue;
          }

          batch.add(keyBytes);

          // 达到批次大小，执行删除
          if (batch.size() >= deleteBatchSize) {
            DeleteBatchResult result = deleteBatch(keyCommands, batch, keepTryingUnlink);
            totalDeleted += result.deleted();
            keepTryingUnlink = result.keepTryingUnlink();
            batch.clear();
          }
        }

        // 删除最后剩余的批次
        if (!batch.isEmpty()) {
          DeleteBatchResult result = deleteBatch(keyCommands, batch, keepTryingUnlink);
          totalDeleted += result.deleted();
        }
      }

      return totalDeleted;
    });
  }

  /**
   * 获取 Redis key 命令接口.
   *
   * <p>当连接未提供 key 命令时抛出 {@link IllegalStateException}，
   * 避免后续操作出现 NPE。</p>
   *
   * @param connection Redis 连接
   * @return Redis key 命令接口
   */
  private RedisKeyCommands keyCommands(RedisConnection connection) {
    RedisKeyCommands keyCommands = connection.keyCommands();
    if (keyCommands == null) {
      throw new IllegalStateException("Redis key commands are not available");
    }
    return keyCommands;
  }

  /**
   * 删除单个批次的 key.
   *
   * <p>优先使用 UNLINK，若失败则输出 WARN 并回退到 DEL。
   * 一旦 UNLINK 失败，后续批次将直接使用 DEL 不再尝试 UNLINK。</p>
   *
   * <p>DEL 失败时直接抛出异常，由 {@code scanAndDeleteInBatches} 传播到
   * {@code clear()} / {@code clearByPrefix()} 的外层 catch 触发
   * {@code recordFailure()}，避免误调 {@code recordSuccess()} 导致降级状态
   * 被错误翻转、进而触发恢复回调清空 L1。</p>
   *
   * @param keyCommands      Redis key 命令接口
   * @param batch            待删除的 key 字节数组列表
   * @param keepTryingUnlink 是否尝试 UNLINK（为 false 时直接用 DEL）
   * @return 批量删除结果（删除数量 + 是否继续尝试 UNLINK）
   * @throws RuntimeException 当 DEL 命令执行失败时抛出，由调用方处理
   */
  private DeleteBatchResult deleteBatch(
      RedisKeyCommands keyCommands,
      List<byte[]> batch,
      boolean keepTryingUnlink) {

    byte[][] keysArray = batch.toArray(new byte[0][]);

    if (keepTryingUnlink) {
      try {
        Long deleted = keyCommands.unlink(keysArray);
        return new DeleteBatchResult(deleted != null ? deleted : 0L, true);
      } catch (Exception e) {
        // UNLINK 失败，输出 WARN 并回退到 DEL
        LOG.warn("UNLINK command failed, falling back to DEL for this batch "
            + "and subsequent batches: {}", e.getMessage());
        keepTryingUnlink = false;
      }
    }

    // 使用 DEL 作为回退方案：失败时不吞异常，传播到外层 catch 触发 recordFailure
    Long deleted = keyCommands.del(keysArray);
    return new DeleteBatchResult(deleted != null ? deleted : 0L, false);
  }

  private String redisKey(String key) {
    return keyPrefix + key;
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
   * <p>如果之前处于降级状态，要求连续 {@link #RECOVERY_THRESHOLD} 次成功才判定恢复，
   * 输出 INFO 级别恢复日志并触发恢复回调。这可以防止 Redis flapping（在可用和不可用
   * 之间快速抖动）时反复触发 recoveryCallback 清空 L1，引发缓存雪崩。</p>
   *
   * <p><b>Fast-path 优化</b>：非降级状态下（{@code degradationWarned == false}），
   * 只需 reset 失败计数后立即返回，不获取锁。绝大多数情况下 Redis 正常运行，
   * 避免无谓的 synchronized 开销。仅在降级状态下才进入 synchronized 块。</p>
   *
   * <p>恢复判断通过 synchronized 保证原子性：多个线程并发成功时，
   * 仅一个线程能将 {@code degradationWarned} 从 true 翻转为 false 并触发回调，
   * 避免回调被重复触发。回调在锁外执行，避免持锁过久。</p>
   */
  private void recordSuccess() {
    consecutiveFailures.set(0);
    // Fast-path：非降级状态下无需计数成功次数或获取锁
    if (!degradationWarned) {
      return;
    }
    // 降级状态下：要求连续 RECOVERY_THRESHOLD 次成功才恢复
    int successes = consecutiveSuccesses.incrementAndGet();
    if (successes < RECOVERY_THRESHOLD) {
      return;
    }
    boolean recovered = false;
    synchronized (this) {
      if (degradationWarned) {
        degradationWarned = false;
        consecutiveSuccesses.set(0);
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
   * <p>降级状态下，失败日志会受到限流，避免每一次失败都输出完整堆栈的 ERROR
   * 日志导致日志文件迅速膨胀。降级期间，每隔 {@code failureLogIntervalSeconds}
   * 秒输出一条 WARN 级别摘要日志，无堆栈。</p>
   *
   * @param operation 操作名称
   * @param key       操作的 key
   * @param e         异常
   */
  private void recordFailure(String operation, String key, Exception e) {
    // 失败时重置成功计数，确保 flapping 场景下不会因累计的成功次数误触发恢复
    consecutiveSuccesses.set(0);
    int failures = consecutiveFailures.incrementAndGet();

    // 阈值前：输出完整 ERROR 日志带堆栈
    if (failures < DEGRADATION_THRESHOLD) {
      LOG.error("Failed to {} from Redis: {}", operation, key, e);
      return;
    }

    // 首次降级：输出降级警告，并记录日志时间戳
    if (!degradationWarned) {
      synchronized (this) {
        if (!degradationWarned) {
          degradationWarned = true;
          lastDegradedFailureLogNanos = System.nanoTime();
          LOG.warn("Bing Cache: Redis L2 cache has failed {} consecutive times, "
              + "degraded to L1-only mode. Check Redis connectivity.",
              failures);
          return;
        }
      }
    }

    // 已降级状态：按间隔限流输出摘要日志，无堆栈
    long now = System.nanoTime();
    long intervalNanos = TimeUnit.SECONDS.toNanos(failureLogIntervalSeconds);
    if (now - lastDegradedFailureLogNanos >= intervalNanos) {
      synchronized (this) {
        if (now - lastDegradedFailureLogNanos >= intervalNanos) {
          lastDegradedFailureLogNanos = now;
          LOG.warn("Bing Cache: Redis L2 cache still degraded. "
                  + "Consecutive failures: {}, Last failed operation: {} on key: {}, "
                  + "Error message: {}",
              consecutiveFailures.get(), operation, key,
              e.getMessage() != null ? e.getMessage() : "null");
        }
      }
    }
  }
}
