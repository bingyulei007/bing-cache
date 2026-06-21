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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisKeyCommands;

/**
 * RedisCacheManager 单元测试.
 */
class RedisCacheManagerTest {

  private RedisTemplate<String, Object> redisTemplate;

  private ValueOperations<String, Object> valueOperations;

  private RedisCacheManager redisCacheManager;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    redisTemplate = mock(RedisTemplate.class);
    valueOperations = mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    redisCacheManager = new RedisCacheManager(redisTemplate, "bing-cache:");
  }

  @Test
  void testGetCacheHit() {
    when(valueOperations.get("bing-cache:user:1")).thenReturn("cached-value");
    Object result = redisCacheManager.get("user:1");
    assertEquals("cached-value", result);
  }

  @Test
  void testGetCacheMiss() {
    when(valueOperations.get("bing-cache:user:1")).thenReturn(null);
    Object result = redisCacheManager.get("user:1");
    assertNull(result);
  }

  @Test
  void testGetHandlesException() {
    when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis error"));
    Object result = redisCacheManager.get("user:1");
    assertNull(result);
  }

  @Test
  void testPutWithExpiry() {
    redisCacheManager.put("user:1", "value", 60);
    verify(valueOperations).set("bing-cache:user:1", "value", 60,
        java.util.concurrent.TimeUnit.SECONDS);
  }

  @Test
  void testPutWithoutExpiry() {
    redisCacheManager.put("user:1", "value", 0);
    verify(valueOperations).set("bing-cache:user:1", "value");
  }

  @Test
  void testPutHandlesException() {
    org.mockito.Mockito.doThrow(new RuntimeException("Redis error"))
        .when(valueOperations).set(anyString(), any(), anyLong(), any());
    // Should not throw
    redisCacheManager.put("user:1", "value", 60);
  }

  @Test
  void testEvict() {
    redisCacheManager.evict("user:1");
    verify(redisTemplate).delete("bing-cache:user:1");
  }

  @Test
  void testEvictHandlesException() {
    when(redisTemplate.delete(anyString())).thenThrow(new RuntimeException("Redis error"));
    // Should not throw
    redisCacheManager.evict("user:1");
  }

  @Test
  void testCustomKeyPrefix() {
    RedisCacheManager customManager = new RedisCacheManager(redisTemplate, "myapp:");
    when(valueOperations.get("myapp:user:1")).thenReturn("value");
    Object result = customManager.get("user:1");
    assertEquals("value", result);
    verify(valueOperations).get("myapp:user:1");
  }

  @Test
  void testCreation() {
    assertNotNull(new RedisCacheManager(redisTemplate, "prefix:"));
  }

  @Test
  void testGetRemainingTtl() {
    when(redisTemplate.getExpire("bing-cache:user:1", TimeUnit.SECONDS)).thenReturn(180L);
    long ttl = redisCacheManager.getRemainingTtl("user:1");
    assertEquals(180L, ttl);
  }

  @Test
  void testGetRemainingTtlNoExpiry() {
    when(redisTemplate.getExpire("bing-cache:user:1", TimeUnit.SECONDS)).thenReturn(-1L);
    long ttl = redisCacheManager.getRemainingTtl("user:1");
    assertEquals(-1L, ttl);
  }

  @Test
  void testGetRemainingTtlKeyNotFound() {
    when(redisTemplate.getExpire("bing-cache:user:1", TimeUnit.SECONDS)).thenReturn(-2L);
    long ttl = redisCacheManager.getRemainingTtl("user:1");
    assertEquals(-2L, ttl);
  }

  @Test
  void testGetRemainingTtlHandlesException() {
    when(redisTemplate.getExpire(anyString(), any(TimeUnit.class)))
        .thenThrow(new RuntimeException("Redis error"));
    long ttl = redisCacheManager.getRemainingTtl("user:1");
    assertEquals(-2L, ttl);
  }

  @Test
  void testDegradationAfterConsecutiveFailures() {
    when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis error"));
    // 连续失败 3 次应触发降级警告
    redisCacheManager.get("key1");
    redisCacheManager.get("key2");
    redisCacheManager.get("key3");
    // 第 4 次仍然是失败，但不应重复警告（降级状态已标记）
    assertNull(redisCacheManager.get("key4"));
  }

  @Test
  void testRecoveryAfterDegradation() {
    // 先连续失败 3 次触发降级
    when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis error"));
    redisCacheManager.get("key1");
    redisCacheManager.get("key2");
    redisCacheManager.get("key3");
    // 恢复正常：使用 doReturn 覆盖之前的 thenThrow
    doReturn("recovered").when(valueOperations).get(anyString());
    Object result = redisCacheManager.get("key4");
    assertEquals("recovered", result);
  }

  @Test
  @SuppressWarnings("unchecked")
  void testClearByPrefix() {
    // 新实现通过 RedisCallback 在连接层执行 SCAN + 删除，不再调用 redisTemplate.delete(List)
    doAnswer(invocation -> {
      RedisCallback<Long> callback = invocation.getArgument(0);
      RedisConnection connection = mock(RedisConnection.class);
      var keyCommands = mock(org.springframework.data.redis.connection.RedisKeyCommands.class);
      when(connection.keyCommands()).thenReturn(keyCommands);

      Cursor<byte[]> cursor = mock(Cursor.class);
      when(cursor.hasNext()).thenReturn(true, true, false);
      when(cursor.next())
          .thenReturn("bing-cache:user([1])".getBytes())
          .thenReturn("bing-cache:user([2])".getBytes());
      when(keyCommands.scan(any(ScanOptions.class))).thenReturn(cursor);
      when(keyCommands.unlink(any(byte[][].class))).thenReturn(2L);

      return callback.doInRedis(connection);
    }).when(redisTemplate).execute(any(RedisCallback.class));

    redisCacheManager.clearByPrefix("user");

    verify(redisTemplate).execute(any(RedisCallback.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void testClearByPrefixHandlesException() {
    org.mockito.Mockito.doThrow(new RuntimeException("Redis error"))
        .when(redisTemplate).execute(any(org.springframework.data.redis.core.RedisCallback.class));
    // Should not throw
    redisCacheManager.clearByPrefix("user");
  }

  // ========== 恢复回调测试 ==========

  /**
   * 测试 Redis 恢复时触发回调.
   *
   * <p>降级状态下需要连续 {@value #RECOVERY_THRESHOLD} 次成功才触发恢复回调，
   * 防止 Redis flapping 反复清空 L1。</p>
   */
  @Test
  void testRecoveryCallbackTriggered() {
    Runnable callback = mock(Runnable.class);
    redisCacheManager.setRecoveryCallback(callback);

    // 先连续失败 3 次触发降级
    when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis error"));
    redisCacheManager.get("key1");
    redisCacheManager.get("key2");
    redisCacheManager.get("key3");

    // 恢复正常：需要连续 3 次成功才触发恢复回调
    doReturn("recovered").when(valueOperations).get(anyString());
    redisCacheManager.get("key4");  // 成功 1
    redisCacheManager.get("key5");  // 成功 2
    redisCacheManager.get("key6");  // 成功 3 → 触发恢复

    // 回调应被触发 1 次
    verify(callback, times(1)).run();
  }

  /**
   * 测试未设置恢复回调时正常恢复（不抛异常）.
   */
  @Test
  void testRecoveryWithoutCallback() {
    // 不设置回调

    // 先连续失败 3 次触发降级
    when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis error"));
    redisCacheManager.get("key1");
    redisCacheManager.get("key2");
    redisCacheManager.get("key3");

    // 恢复正常，不应抛异常
    doReturn("recovered").when(valueOperations).get(anyString());
    Object result = redisCacheManager.get("key4");
    assertEquals("recovered", result);
  }

  /**
   * 测试恢复回调执行异常不影响后续操作.
   *
   * <p>降级状态下连续 3 次成功触发恢复，回调抛异常不应影响 get() 返回值。</p>
   */
  @Test
  void testRecoveryCallbackExceptionDoesNotAffectOperation() {
    Runnable callback = mock(Runnable.class);
    redisCacheManager.setRecoveryCallback(callback);

    // 先连续失败 3 次触发降级
    when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis error"));
    redisCacheManager.get("key1");
    redisCacheManager.get("key2");
    redisCacheManager.get("key3");

    // 回调会抛异常
    org.mockito.Mockito.doThrow(new RuntimeException("callback error"))
        .when(callback).run();

    // 恢复正常：连续 3 次成功，第 3 次触发恢复回调
    doReturn("recovered").when(valueOperations).get(anyString());
    redisCacheManager.get("key4");  // 成功 1
    redisCacheManager.get("key5");  // 成功 2
    // 不应抛异常（回调在锁外执行，异常被 catch）
    Object result = redisCacheManager.get("key6");  // 成功 3 → 触发恢复
    assertEquals("recovered", result);
  }

  // ========== Flapping 保护测试 ==========

  /**
   * 测试 Redis flapping 场景下不会反复触发恢复回调.
   *
   * <p>场景：Redis 在可用和不可用之间抖动。
   * 降级后 2 次成功（未达 RECOVERY_THRESHOLD=3）→ 1 次失败（reset 成功计数）→
   * 2 次成功（仍不够）→ 1 次失败 → ... 始终不触发 recoveryCallback。
   * 这避免了 flapping 期间反复清空 L1 导致的缓存雪崩。</p>
   */
  @Test
  void testFlappingDoesNotTriggerRecovery() {
    Runnable callback = mock(Runnable.class);
    redisCacheManager.setRecoveryCallback(callback);

    // 3 次失败触发降级
    when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis error"));
    redisCacheManager.get("f1");
    redisCacheManager.get("f2");
    redisCacheManager.get("f3");

    // flapping 循环：2 次成功 + 1 次失败，重复 3 轮
    for (int round = 0; round < 3; round++) {
      // 2 次成功（未达 RECOVERY_THRESHOLD=3，不触发恢复）
      doReturn("ok").when(valueOperations).get(anyString());
      redisCacheManager.get("s" + round + "_1");
      redisCacheManager.get("s" + round + "_2");

      // 1 次失败（reset 成功计数）
      when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis error"));
      redisCacheManager.get("f" + round);
    }

    // 整个 flapping 过程中不应触发恢复回调
    verify(callback, never()).run();
  }

  /**
   * 测试 flapping 后连续 3 次成功才触发恢复.
   */
  @Test
  void testRecoveryAfterFlapping() {
    Runnable callback = mock(Runnable.class);
    redisCacheManager.setRecoveryCallback(callback);

    // 3 次失败触发降级
    when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis error"));
    redisCacheManager.get("f1");
    redisCacheManager.get("f2");
    redisCacheManager.get("f3");

    // 2 次成功后 1 次失败（flapping）
    doReturn("ok").when(valueOperations).get(anyString());
    redisCacheManager.get("s1");
    redisCacheManager.get("s2");
    when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis error"));
    redisCacheManager.get("f4");

    // 此时成功计数已被 reset，需要重新连续 3 次成功
    doReturn("ok").when(valueOperations).get(anyString());
    redisCacheManager.get("s3");  // 成功 1
    redisCacheManager.get("s4");  // 成功 2
    verify(callback, never()).run();  // 还没到 3 次

    redisCacheManager.get("s5");  // 成功 3 → 触发恢复
    verify(callback, times(1)).run();
  }

  // ========== 构造器默认值测试 ==========

  /**
   * 测试 2 参数构造器使用正确的默认值.
   *
   * <p>默认值应与 BingCacheProperties.Redis 一致：
   * scanCount=1000, deleteBatchSize=500, useUnlink=true, failureLogInterval=30</p>
   */
  @Test
  void testTwoArgConstructorDefaults() {
    RedisCacheManager manager = new RedisCacheManager(redisTemplate, "prefix:");
    assertEquals(1000L, manager.getScanCount());
    assertEquals(500L, manager.getDeleteBatchSize());
    assertEquals(true, manager.isUseUnlink());
    assertEquals(30L, manager.getFailureLogInterval());
  }

  // ========== SCAN 批量删除测试 ==========

  /**
   * 测试 clear 使用配置的 scanCount 和 deleteBatchSize，分多批次删除.
   *
   * <p>5 个 key，deleteBatchSize=2，应分 3 批次删除：2, 2, 1</p>
   *
   * <p>使用 DEL 而非 UNLINK 来避免 Mockito 数组参数匹配问题</p>
   */
  @Test
  @SuppressWarnings("unchecked")
  void testClearUsesScanCountAndBatchSize() {
    // 使用 useUnlink=false 来直接使用 DEL，避免 UNLINK 的 Mockito 数组参数匹配问题
    RedisCacheManager manager = new RedisCacheManager(
        redisTemplate, "bing-cache:", 10L, 2L, false, 30L);

    // 使用 ArgumentCaptor 来捕获批次参数
    ArgumentCaptor<byte[][]> keysCaptor = ArgumentCaptor.forClass(byte[][].class);

    doAnswer(invocation -> {
      RedisCallback<Long> callback = invocation.getArgument(0);
      RedisConnection connection = mock(RedisConnection.class);
      RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
      when(connection.keyCommands()).thenReturn(keyCommands);

      // 5 个 key，分 3 批次
      Cursor<byte[]> cursor = mock(Cursor.class);
      when(cursor.hasNext()).thenReturn(true, true, true, true, true, false);
      when(cursor.next())
          .thenReturn("bing-cache:key1".getBytes())
          .thenReturn("bing-cache:key2".getBytes())
          .thenReturn("bing-cache:key3".getBytes())
          .thenReturn("bing-cache:key4".getBytes())
          .thenReturn("bing-cache:key5".getBytes());
      when(keyCommands.scan(any(ScanOptions.class))).thenReturn(cursor);

      // 使用 doAnswer 来正确处理数组参数匹配
      // 注意：不要用 inv.getArgument(0) 获取 varargs 参数，Mockito 对 del(byte[]... keys)
      // 的 getArgument(0) 返回单个 byte[] 而非整个 byte[][]，会导致 ClassCastException。
      // 批次大小验证由 keysCaptor 在调用后完成。
      doAnswer(inv -> 2L).when(keyCommands).del(keysCaptor.capture());

      return callback.doInRedis(connection);
    }).when(redisTemplate).execute(any(RedisCallback.class));

    manager.clear();

    // 验证批次数量：5 个 key，batchSize=2，应分 3 批次
    assertEquals(3, keysCaptor.getAllValues().size(), "5 keys with batchSize=2 should require 3 batches");

    // 验证每批次的大小：2, 2, 1
    assertEquals(2, keysCaptor.getAllValues().get(0).length, "First batch should have 2 keys");
    assertEquals(2, keysCaptor.getAllValues().get(1).length, "Second batch should have 2 keys");
    assertEquals(1, keysCaptor.getAllValues().get(2).length, "Third batch should have 1 key");
  }

  /**
   * 测试 clearByPrefix 使用正确的 pattern "bing-cache:user*".
   */
  @Test
  @SuppressWarnings("unchecked")
  void testClearByPrefixUsesCorrectPattern() {
    RedisCacheManager manager = new RedisCacheManager(
        redisTemplate, "bing-cache:", 10L, 10L, true, 30L);

    AtomicInteger patternMatches = new AtomicInteger(0);

    doAnswer(invocation -> {
      RedisCallback<Long> callback = invocation.getArgument(0);
      RedisConnection connection = mock(RedisConnection.class);
      RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
      when(connection.keyCommands()).thenReturn(keyCommands);

      Cursor<byte[]> cursor = mock(Cursor.class);
      when(cursor.hasNext()).thenReturn(true, false);
      when(cursor.next()).thenReturn("bing-cache:user:1".getBytes());

      // 捕获并验证 pattern
      ArgumentCaptor<ScanOptions> scanCaptor = ArgumentCaptor.forClass(ScanOptions.class);
      when(keyCommands.scan(scanCaptor.capture())).thenAnswer(inv -> {
        ScanOptions opts = scanCaptor.getValue();
        String pattern = new String(opts.getPattern());
        if ("bing-cache:user*".equals(pattern)) {
          patternMatches.incrementAndGet();
        }
        return cursor;
      });

      when(keyCommands.unlink(any(byte[][].class))).thenReturn(1L);

      return callback.doInRedis(connection);
    }).when(redisTemplate).execute(any(RedisCallback.class));

    manager.clearByPrefix("user");

    // 验证使用了正确的 pattern
    assertEquals(1, patternMatches.get());
  }

  /**
   * 测试 prefix 含 glob 元字符时，字面前缀二次过滤生效，不误删其他命名空间的 key.
   *
   * <p>场景：prefix="user*"（含 *）。Redis SCAN glob 模式 "bing-cache:user**"
   * 会匹配 "bing-cache:userCache(1)" 和 "bing-cache:userProfile(1)"，
   * 但只有字面前缀 "bing-cache:user*" 匹配的 key 才应被删除。
   * 此处模拟两个 key 都被 SCAN 返回，验证只删除字面匹配的。</p>
   */
  @Test
  @SuppressWarnings("unchecked")
  void testClearByPrefixWithGlobMetaCharFilteredByLiteralPrefix() {
    RedisCacheManager manager = new RedisCacheManager(
        redisTemplate, "bing-cache:", 10L, 10L, true, 30L);

    AtomicInteger unlinkCallCount = new AtomicInteger(0);
    ArgumentCaptor<byte[][]> keysCaptor = ArgumentCaptor.forClass(byte[][].class);

    doAnswer(invocation -> {
      RedisCallback<Long> callback = invocation.getArgument(0);
      RedisConnection connection = mock(RedisConnection.class);
      RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
      when(connection.keyCommands()).thenReturn(keyCommands);

      // SCAN 返回两个 key：一个字面匹配 "bing-cache:user*"，一个不匹配
      Cursor<byte[]> cursor = mock(Cursor.class);
      when(cursor.hasNext()).thenReturn(true, true, false);
      when(cursor.next())
          .thenReturn("bing-cache:user*special".getBytes())  // 字面前缀匹配
          .thenReturn("bing-cache:userCache(1)".getBytes());  // glob 误匹配，应跳过
      when(keyCommands.scan(any(ScanOptions.class))).thenReturn(cursor);

      when(keyCommands.unlink(keysCaptor.capture())).thenAnswer(inv -> {
        unlinkCallCount.incrementAndGet();
        return 1L;
      });

      return callback.doInRedis(connection);
    }).when(redisTemplate).execute(any(RedisCallback.class));

    manager.clearByPrefix("user*");

    // 验证只删除了 1 个 key（字面匹配的），glob 误匹配的被过滤
    assertEquals(1, unlinkCallCount.get(), "Only literal-prefix-matched key should be deleted");
    byte[][] deletedKeys = keysCaptor.getAllValues().get(0);
    assertEquals(1, deletedKeys.length, "Batch should contain only 1 key after literal filter");
    assertEquals("bing-cache:user*special", new String(deletedKeys[0]),
        "Deleted key should be the literal-prefix-matched one");
  }

  /**
   * 测试正常 prefix（不含 glob 元字符）不受字面前缀过滤影响.
   *
   * <p>场景：prefix="user"。SCAN 返回 3 个 "bing-cache:user" 开头的 key，
   * 都应被删除，验证正常场景下二次过滤不会误删。</p>
   */
  @Test
  @SuppressWarnings("unchecked")
  void testClearByPrefixNormalPrefixUnaffectedByLiteralFilter() {
    RedisCacheManager manager = new RedisCacheManager(
        redisTemplate, "bing-cache:", 10L, 10L, true, 30L);

    AtomicInteger unlinkCallCount = new AtomicInteger(0);

    doAnswer(invocation -> {
      RedisCallback<Long> callback = invocation.getArgument(0);
      RedisConnection connection = mock(RedisConnection.class);
      RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
      when(connection.keyCommands()).thenReturn(keyCommands);

      Cursor<byte[]> cursor = mock(Cursor.class);
      when(cursor.hasNext()).thenReturn(true, true, true, false);
      when(cursor.next())
          .thenReturn("bing-cache:user:1".getBytes())
          .thenReturn("bing-cache:user:2".getBytes())
          .thenReturn("bing-cache:userProfile(1)".getBytes());
      when(keyCommands.scan(any(ScanOptions.class))).thenReturn(cursor);

      when(keyCommands.unlink(any(byte[][].class))).thenAnswer(inv -> {
        unlinkCallCount.incrementAndGet();
        return 1L;
      });

      return callback.doInRedis(connection);
    }).when(redisTemplate).execute(any(RedisCallback.class));

    manager.clearByPrefix("user");

    // 3 个 key 都以 "bing-cache:user" 字面开头，都应被删除
    assertEquals(1, unlinkCallCount.get(), "Should call UNLINK once for the batch");
  }

  /**
   * 测试 clear() 不受字面前缀过滤影响（全清场景）.
   *
   * <p>clear() 传 literalPrefix=null，所有 SCAN 返回的 key 都应被删除。</p>
   */
  @Test
  @SuppressWarnings("unchecked")
  void testClearSkipsLiteralFilter() {
    RedisCacheManager manager = new RedisCacheManager(
        redisTemplate, "bing-cache:", 10L, 10L, true, 30L);

    AtomicInteger unlinkCallCount = new AtomicInteger(0);

    doAnswer(invocation -> {
      RedisCallback<Long> callback = invocation.getArgument(0);
      RedisConnection connection = mock(RedisConnection.class);
      RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
      when(connection.keyCommands()).thenReturn(keyCommands);

      // SCAN 返回不同前缀的 key（包含 glob 元字符）
      Cursor<byte[]> cursor = mock(Cursor.class);
      when(cursor.hasNext()).thenReturn(true, true, true, false);
      when(cursor.next())
          .thenReturn("bing-cache:user:1".getBytes())
          .thenReturn("bing-cache:order*1".getBytes())
          .thenReturn("bing-cache:product[1]".getBytes());
      when(keyCommands.scan(any(ScanOptions.class))).thenReturn(cursor);

      when(keyCommands.unlink(any(byte[][].class))).thenAnswer(inv -> {
        unlinkCallCount.incrementAndGet();
        return 3L;
      });

      return callback.doInRedis(connection);
    }).when(redisTemplate).execute(any(RedisCallback.class));

    manager.clear();

    // 全清场景：所有 key 都应被删除，无二次过滤
    assertEquals(1, unlinkCallCount.get(), "Should call UNLINK once for the batch");
  }

  /**
   * 测试 useUnlink=true 时优先使用 UNLINK，不调用 DEL.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testUseUnlinkTruePrefersUnlink() {
    RedisCacheManager manager = new RedisCacheManager(
        redisTemplate, "bing-cache:", 10L, 10L, true, 30L);

    AtomicInteger unlinkCallCount = new AtomicInteger(0);
    AtomicInteger delCallCount = new AtomicInteger(0);

    doAnswer(invocation -> {
      RedisCallback<Long> callback = invocation.getArgument(0);
      RedisConnection connection = mock(RedisConnection.class);
      RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
      when(connection.keyCommands()).thenReturn(keyCommands);

      Cursor<byte[]> cursor = mock(Cursor.class);
      when(cursor.hasNext()).thenReturn(true, false);
      when(cursor.next()).thenReturn("bing-cache:key1".getBytes());
      when(keyCommands.scan(any(ScanOptions.class))).thenReturn(cursor);

      when(keyCommands.unlink(any(byte[][].class))).thenAnswer(inv -> {
        unlinkCallCount.incrementAndGet();
        return 1L;
      });

      when(keyCommands.del(any(byte[][].class))).thenAnswer(inv -> {
        delCallCount.incrementAndGet();
        return 1L;
      });

      return callback.doInRedis(connection);
    }).when(redisTemplate).execute(any(RedisCallback.class));

    manager.clear();

    // 验证只调用了 UNLINK，没有调用 DEL
    assertEquals(1, unlinkCallCount.get());
    assertEquals(0, delCallCount.get());
  }

  /**
   * 测试 UNLINK 失败时回退到 DEL，并对后续批次使用 DEL.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testUnlinkFailureFallsBackToDel() {
    RedisCacheManager manager = new RedisCacheManager(
        redisTemplate, "bing-cache:", 10L, 2L, true, 30L);

    AtomicInteger unlinkCallCount = new AtomicInteger(0);
    AtomicInteger delCallCount = new AtomicInteger(0);

    doAnswer(invocation -> {
      RedisCallback<Long> callback = invocation.getArgument(0);
      RedisConnection connection = mock(RedisConnection.class);
      RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
      when(connection.keyCommands()).thenReturn(keyCommands);

      // 返回 4 个 key，分 2 批次
      Cursor<byte[]> cursor = mock(Cursor.class);
      when(cursor.hasNext()).thenReturn(true, true, true, true, false);
      when(cursor.next())
          .thenReturn("bing-cache:key1".getBytes())
          .thenReturn("bing-cache:key2".getBytes())
          .thenReturn("bing-cache:key3".getBytes())
          .thenReturn("bing-cache:key4".getBytes());
      when(keyCommands.scan(any(ScanOptions.class))).thenReturn(cursor);

      // 第一批次 UNLINK 失败，第二批次直接用 DEL
      when(keyCommands.unlink(any(byte[][].class))).thenAnswer(inv -> {
        unlinkCallCount.incrementAndGet();
        throw new UnsupportedOperationException("UNLINK not supported");
      });

      when(keyCommands.del(any(byte[][].class))).thenAnswer(inv -> {
        delCallCount.incrementAndGet();
        // 不用 inv.getArgument(0)：Mockito 对 varargs del(byte[]... keys) 的
        // getArgument(0) 返回单个 byte[] 而非 byte[][]，会抛 ClassCastException
        return 2L;
      });

      return callback.doInRedis(connection);
    }).when(redisTemplate).execute(any(RedisCallback.class));

    manager.clear();

    // 验证 UNLINK 只尝试了 1 次失败，后续 2 批次都用 DEL
    assertEquals(1, unlinkCallCount.get());
    assertEquals(2, delCallCount.get());
  }

  /**
   * 测试 useUnlink=false 时直接使用 DEL，不调用 UNLINK.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testUseUnlinkFalseUsesDelDirectly() {
    RedisCacheManager manager = new RedisCacheManager(
        redisTemplate, "bing-cache:", 10L, 10L, false, 30L);

    AtomicInteger unlinkCallCount = new AtomicInteger(0);
    AtomicInteger delCallCount = new AtomicInteger(0);

    doAnswer(invocation -> {
      RedisCallback<Long> callback = invocation.getArgument(0);
      RedisConnection connection = mock(RedisConnection.class);
      RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
      when(connection.keyCommands()).thenReturn(keyCommands);

      Cursor<byte[]> cursor = mock(Cursor.class);
      when(cursor.hasNext()).thenReturn(true, false);
      when(cursor.next()).thenReturn("bing-cache:key1".getBytes());
      when(keyCommands.scan(any(ScanOptions.class))).thenReturn(cursor);

      when(keyCommands.unlink(any(byte[][].class))).thenAnswer(inv -> {
        unlinkCallCount.incrementAndGet();
        return 1L;
      });

      when(keyCommands.del(any(byte[][].class))).thenAnswer(inv -> {
        delCallCount.incrementAndGet();
        return 1L;
      });

      return callback.doInRedis(connection);
    }).when(redisTemplate).execute(any(RedisCallback.class));

    manager.clear();

    // 验证只调用了 DEL，没有调用 UNLINK
    assertEquals(0, unlinkCallCount.get());
    assertEquals(1, delCallCount.get());
  }

  // ========== null 返回值测试 ==========

  /**
   * 测试 UNLINK 返回 null 时 clear 不抛异常并正常完成.
   *
   * <p>某些 Redis 客户端/驱动在特殊情况下可能对 UNLINK 返回 null，
   * 生产代码应处理 null 返回值，避免 NPE。</p>
   */
  @Test
  @SuppressWarnings("unchecked")
  void testClearDoesNotThrowWhenUnlinkReturnsNull() {
    RedisCacheManager manager = new RedisCacheManager(
        redisTemplate, "bing-cache:", 10L, 10L, true, 30L);

    doAnswer(invocation -> {
      RedisCallback<Long> callback = invocation.getArgument(0);
      RedisConnection connection = mock(RedisConnection.class);
      RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
      when(connection.keyCommands()).thenReturn(keyCommands);

      Cursor<byte[]> cursor = mock(Cursor.class);
      when(cursor.hasNext()).thenReturn(true, false);
      when(cursor.next()).thenReturn("bing-cache:key1".getBytes());
      when(keyCommands.scan(any(ScanOptions.class))).thenReturn(cursor);

      // UNLINK 返回 null - 生产代码应处理这种情况
      when(keyCommands.unlink(any(byte[][].class))).thenReturn(null);

      return callback.doInRedis(connection);
    }).when(redisTemplate).execute(any(RedisCallback.class));

    // 不应抛异常
    manager.clear();

    // 验证操作成功执行
    verify(redisTemplate).execute(any(RedisCallback.class));
  }

  /**
   * 测试 DEL 返回 null 时 clear 不抛异常并正常完成.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testClearDoesNotThrowWhenDelReturnsNull() {
    RedisCacheManager manager = new RedisCacheManager(
        redisTemplate, "bing-cache:", 10L, 10L, false, 30L);

    doAnswer(invocation -> {
      RedisCallback<Long> callback = invocation.getArgument(0);
      RedisConnection connection = mock(RedisConnection.class);
      RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
      when(connection.keyCommands()).thenReturn(keyCommands);

      Cursor<byte[]> cursor = mock(Cursor.class);
      when(cursor.hasNext()).thenReturn(true, false);
      when(cursor.next()).thenReturn("bing-cache:key1".getBytes());
      when(keyCommands.scan(any(ScanOptions.class))).thenReturn(cursor);

      // DEL 返回 null
      when(keyCommands.del(any(byte[][].class))).thenReturn(null);

      return callback.doInRedis(connection);
    }).when(redisTemplate).execute(any(RedisCallback.class));

    // 不应抛异常
    manager.clear();

    // 验证操作成功执行
    verify(redisTemplate).execute(any(RedisCallback.class));
  }

  /**
   * 测试 UNLINK 失败回退到 DEL 且 DEL 返回 null 时不抛异常.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testClearDoesNotThrowWhenUnlinkFailsAndDelReturnsNull() {
    RedisCacheManager manager = new RedisCacheManager(
        redisTemplate, "bing-cache:", 10L, 2L, true, 30L);

    doAnswer(invocation -> {
      RedisCallback<Long> callback = invocation.getArgument(0);
      RedisConnection connection = mock(RedisConnection.class);
      RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
      when(connection.keyCommands()).thenReturn(keyCommands);

      Cursor<byte[]> cursor = mock(Cursor.class);
      when(cursor.hasNext()).thenReturn(true, true, false);
      when(cursor.next())
          .thenReturn("bing-cache:key1".getBytes())
          .thenReturn("bing-cache:key2".getBytes());
      when(keyCommands.scan(any(ScanOptions.class))).thenReturn(cursor);

      // UNLINK 失败
      when(keyCommands.unlink(any(byte[][].class)))
          .thenThrow(new UnsupportedOperationException("UNLINK not supported"));

      // DEL 返回 null
      when(keyCommands.del(any(byte[][].class))).thenReturn(null);

      return callback.doInRedis(connection);
    }).when(redisTemplate).execute(any(RedisCallback.class));

    // 不应抛异常
    manager.clear();

    // 验证操作成功执行
    verify(redisTemplate).execute(any(RedisCallback.class));
  }

  /**
   * 测试多批次且部分批次返回 null 时 clear 正常完成.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testClearWithNullResultInMultiBatch() {
    RedisCacheManager manager = new RedisCacheManager(
        redisTemplate, "bing-cache:", 10L, 2L, false, 30L);

    // 使用 ArgumentCaptor 来捕获批次参数
    ArgumentCaptor<byte[][]> keysCaptor = ArgumentCaptor.forClass(byte[][].class);
    final int[] callCount = {0};

    doAnswer(invocation -> {
      RedisCallback<Long> callback = invocation.getArgument(0);
      RedisConnection connection = mock(RedisConnection.class);
      RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
      when(connection.keyCommands()).thenReturn(keyCommands);

      // 5 个 key，分 3 批次
      Cursor<byte[]> cursor = mock(Cursor.class);
      when(cursor.hasNext()).thenReturn(true, true, true, true, true, false);
      when(cursor.next())
          .thenReturn("bing-cache:key1".getBytes())
          .thenReturn("bing-cache:key2".getBytes())
          .thenReturn("bing-cache:key3".getBytes())
          .thenReturn("bing-cache:key4".getBytes())
          .thenReturn("bing-cache:key5".getBytes());
      when(keyCommands.scan(any(ScanOptions.class))).thenReturn(cursor);

      // 第一批次返回 null，其他批次返回固定值
      // 不用 inv.getArgument(0)：Mockito 对 varargs del(byte[]... keys) 的
      // getArgument(0) 返回单个 byte[] 而非 byte[][]，会抛 ClassCastException
      doAnswer(invDel -> {
        callCount[0]++;
        return callCount[0] == 1 ? null : 2L;
      }).when(keyCommands).del(keysCaptor.capture());

      return callback.doInRedis(connection);
    }).when(redisTemplate).execute(any(RedisCallback.class));

    // 不应抛异常
    manager.clear();

    // 验证 3 批次都执行了
    assertEquals(3, keysCaptor.getAllValues().size(), "3 batches should be executed");
    // 验证每批次的大小：2, 2, 1
    assertEquals(2, keysCaptor.getAllValues().get(0).length, "First batch should have 2 keys");
    assertEquals(2, keysCaptor.getAllValues().get(1).length, "Second batch should have 2 keys");
    assertEquals(1, keysCaptor.getAllValues().get(2).length, "Third batch should have 1 key");
    verify(redisTemplate).execute(any(RedisCallback.class));
  }

  /**
   * 测试 DEL 抛异常时 clear() 不抛异常，且通过 recordFailure 记录失败而非误触 recordSuccess.
   *
   * <p>场景：Redis 读可用但写失败（如主从切换瞬间、ACL 只读）。
   * 若 deleteBatch 吞掉 DEL 异常，clear() 会误调 recordSuccess()，
   * 可能错误翻转降级状态并触发 recoveryCallback 清空 L1。
   * 修复后 DEL 异常传播到外层 catch，触发 recordFailure()。</p>
   */
  @Test
  @SuppressWarnings("unchecked")
  void testClearRecordsFailureWhenDelThrows() {
    // 使用 useUnlink=false 直接走 DEL 路径
    RedisCacheManager manager = new RedisCacheManager(
        redisTemplate, "bing-cache:", 10L, 10L, false, 30L);

    doAnswer(invocation -> {
      RedisCallback<Long> callback = invocation.getArgument(0);
      RedisConnection connection = mock(RedisConnection.class);
      RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
      when(connection.keyCommands()).thenReturn(keyCommands);

      Cursor<byte[]> cursor = mock(Cursor.class);
      when(cursor.hasNext()).thenReturn(true, false);
      when(cursor.next()).thenReturn("bing-cache:key1".getBytes());
      when(keyCommands.scan(any(ScanOptions.class))).thenReturn(cursor);

      // DEL 抛异常
      when(keyCommands.del(any(byte[][].class)))
          .thenThrow(new RuntimeException("Redis write failed"));

      return callback.doInRedis(connection);
    }).when(redisTemplate).execute(any(RedisCallback.class));

    // 不应抛异常（外层 catch 兜住）
    manager.clear();

    // 验证 recordFailure 被调用：阈值前应输出 "Failed to clear from Redis" ERROR 日志
    List<ILoggingEvent> logs = logAppender.list;
    boolean hasClearFailureLog = logs.stream()
        .filter(e -> e.getLevel() == Level.ERROR)
        .anyMatch(e -> e.getFormattedMessage().contains("Failed to clear from Redis"));
    assertTrue(hasClearFailureLog,
        "DEL failure should propagate to recordFailure and log ERROR");

    // 验证没有误触恢复（未设置 recoveryCallback，若误触 recordSuccess 也不会报错，
    // 但 consecutiveFailures 应为 1 而非 0）
    // 通过再次失败验证：连续 2 次 clear 失败后，第 3 次应触发降级 WARN
    manager.clear();
    manager.clear();
    boolean hasDegradationWarn = logs.stream()
        .filter(e -> e.getLevel() == Level.WARN)
        .anyMatch(e -> e.getFormattedMessage().contains("degraded to L1-only mode"));
    assertTrue(hasDegradationWarn,
        "Three consecutive DEL failures should trigger degradation WARN");
  }

  // ========== 失败日志限流测试 ==========

  private ListAppender<ILoggingEvent> logAppender;
  private Logger cacheLogger;

  /**
   * 配置 Logback ListAppender 用于捕获日志.
   */
  @BeforeEach
  void setUpLogAppender() {
    cacheLogger = (Logger) LoggerFactory.getLogger(RedisCacheManager.class);
    logAppender = new ListAppender<>();
    logAppender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
    cacheLogger.addAppender(logAppender);
    logAppender.start();
  }

  @AfterEach
  void tearDownLogAppender() {
    if (logAppender != null) {
      logAppender.stop();
      cacheLogger.detachAppender(logAppender);
    }
  }

  /**
   * 测试连续失败时日志限流：降级前每个失败输出 ERROR，降级后只输出摘要日志.
   *
   * <p>5 次连续失败应产生：2 条 ERROR + 1 条降级 WARN</p>
   */
  @Test
  void testFailureLogThrottling() {
    // 使用小的失败日志间隔以便测试
    RedisCacheManager manager = new RedisCacheManager(
        redisTemplate, "bing-cache:", 10L, 10L, true, 1L);

    when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis connection failed"));

    // 连续调用 5 次 get，每次都失败
    for (int i = 0; i < 5; i++) {
      manager.get("key" + i);
    }

    // 统计 ERROR 和 WARN 日志数量
    List<ILoggingEvent> logs = logAppender.list;
    long errorCount = logs.stream()
        .filter(e -> e.getLevel() == Level.ERROR)
        .count();
    long warnCount = logs.stream()
        .filter(e -> e.getLevel() == Level.WARN)
        .count();

    // 降级阈值前（前 2 次失败）输出 ERROR 日志，共 2 条
    assertEquals(2, errorCount, "Should have 2 ERROR logs for pre-degradation failures");
    // 第 3 次失败触发降级 WARN，降级后 2 次失败因间隔短（1秒内连续调用）被限流，共 1 条 WARN
    assertEquals(1, warnCount, "Should have 1 WARN log for degradation (summary logs suppressed by interval)");

    // 验证降级 WARN 日志内容
    List<String> warnMessages = logs.stream()
        .filter(e -> e.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .collect(Collectors.toList());
    assertTrue(warnMessages.get(0).contains("degraded to L1-only mode"),
        "First WARN should be degradation message");
  }

  /**
   * 测试恢复后再失败重新开始限流计数.
   *
   * <p>降级状态下需要连续 3 次成功才恢复。恢复后失败计数和成功计数都被 reset，
   * 再次连续失败 3 次会重新触发降级。</p>
   */
  @Test
  void testRecoveryResetsFailureCount() {
    Runnable callback = mock(Runnable.class);
    RedisCacheManager manager = new RedisCacheManager(
        redisTemplate, "bing-cache:", 10L, 10L, true, 1L);
    manager.setRecoveryCallback(callback);

    // 失败 3 次触发降级
    when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis connection failed"));
    for (int i = 0; i < 3; i++) {
      manager.get("key" + i);
    }

    // 恢复成功：需要连续 3 次成功才恢复
    doReturn("value").when(valueOperations).get(anyString());
    for (int i = 0; i < 3; i++) {
      manager.get("recovery-key" + i);
    }

    // 验证恢复 INFO 日志
    List<ILoggingEvent> logsBeforeSecondFailure = new ArrayList<>(logAppender.list);
    long recoveryInfoCount = logsBeforeSecondFailure.stream()
        .filter(e -> e.getLevel() == Level.INFO)
        .filter(e -> e.getFormattedMessage().contains("recovered"))
        .count();
    assertEquals(1, recoveryInfoCount, "Should have 1 recovery INFO log");
    verify(callback, times(1)).run();

    // 再次失败 3 次，应重新触发 ERROR 日志
    when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis down again"));
    for (int i = 0; i < 3; i++) {
      manager.get("key" + i);
    }

    // 总 ERROR 日志数：第一次降级前 2 + 第二次降级前 2 = 4
    long totalErrorCount = logAppender.list.stream()
        .filter(e -> e.getLevel() == Level.ERROR)
        .count();
    assertEquals(4, totalErrorCount, "Should have 4 ERROR logs after two failure cycles");
  }

  /**
   * 测试降级状态下，超过日志间隔后输出摘要日志.
   *
   * <p>通过反射手动设置 lastDegradedFailureLogNanos 模拟时间流逝，
   * 避免使用 Thread.sleep()</p>
   */
  @Test
  void testDegradedSummaryLogAfterInterval() throws Exception {
    // 使用较长的间隔，确保不会因为测试执行太快而自动输出
    RedisCacheManager manager = new RedisCacheManager(
        redisTemplate, "bing-cache:", 10L, 10L, true, 30L);

    when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis connection failed"));

    // 失败 3 次触发降级
    for (int i = 0; i < 3; i++) {
      manager.get("key" + i);
    }

    // 记录此时的日志数
    int logsAfterDegradation = logAppender.list.size();

    // 通过反射将 lastDegradedFailureLogNanos 设置为 31 秒前，模拟间隔已过
    Field lastLogNanosField = RedisCacheManager.class.getDeclaredField("lastDegradedFailureLogNanos");
    lastLogNanosField.setAccessible(true);
    long thirtyOneSecondsAgo = System.nanoTime() - TimeUnit.SECONDS.toNanos(31L);
    lastLogNanosField.setLong(manager, thirtyOneSecondsAgo);

    // 再次失败一次 - 应该输出摘要 WARN
    manager.get("key4");

    // 验证新增了一条 WARN 摘要日志
    List<ILoggingEvent> allLogs = logAppender.list;
    assertEquals(logsAfterDegradation + 1, allLogs.size(), "Should have one more log after interval");

    // 验证最后一条是摘要 WARN（无堆栈）
    ILoggingEvent lastLog = allLogs.get(allLogs.size() - 1);
    assertEquals(Level.WARN, lastLog.getLevel());
    assertTrue(lastLog.getFormattedMessage().contains("still degraded"),
        "Log should be degraded summary message");
    assertNull(lastLog.getThrowableProxy(), "Summary log should not have stack trace");
  }

  /**
   * 测试构造器验证：failureLogIntervalSeconds <= 0 应抛出 IllegalArgumentException.
   */
  @Test
  void testConstructorRejectsZeroFailureLogInterval() {
    assertThrows(IllegalArgumentException.class, () ->
        new RedisCacheManager(redisTemplate, "bing-cache:", 10L, 10L, true, 0L));
  }

  @Test
  void testConstructorRejectsNegativeFailureLogInterval() {
    assertThrows(IllegalArgumentException.class, () ->
        new RedisCacheManager(redisTemplate, "bing-cache:", 10L, 10L, true, -5L));
  }
}
