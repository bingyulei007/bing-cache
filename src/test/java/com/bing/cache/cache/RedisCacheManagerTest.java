package com.bing.cache.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

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
    java.util.List<String> keys = java.util.Arrays.asList(
        "bing-cache:user([1])", "bing-cache:user([2])");
    doReturn(keys).when(redisTemplate)
        .execute(any(org.springframework.data.redis.core.RedisCallback.class));

    redisCacheManager.clearByPrefix("user");

    verify(redisTemplate).delete(keys);
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

    // 恢复正常
    doReturn("recovered").when(valueOperations).get(anyString());
    redisCacheManager.get("key4");

    // 回调应被触发
    verify(callback).run();
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

    // 恢复正常
    doReturn("recovered").when(valueOperations).get(anyString());
    // 不应抛异常
    Object result = redisCacheManager.get("key4");
    assertEquals("recovered", result);
  }
}
