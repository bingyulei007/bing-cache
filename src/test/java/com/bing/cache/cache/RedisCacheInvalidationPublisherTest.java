package com.bing.cache.cache;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * RedisCacheInvalidationPublisher 单元测试.
 */
class RedisCacheInvalidationPublisherTest {

  private StringRedisTemplate stringRedisTemplate;

  private RedisCacheInvalidationPublisher publisher;

  @BeforeEach
  void setUp() {
    stringRedisTemplate = mock(StringRedisTemplate.class);
    publisher = new RedisCacheInvalidationPublisher(stringRedisTemplate, "bing-cache:invalidation");
  }

  @Test
  void testPublishEvict() {
    publisher.publishEvict("user:1");
    verify(stringRedisTemplate).convertAndSend(eq("bing-cache:invalidation"), anyString());
  }

  @Test
  void testPublishClear() {
    publisher.publishClear();
    verify(stringRedisTemplate).convertAndSend(eq("bing-cache:invalidation"), anyString());
  }

  @Test
  void testPublishEvictWithCustomChannel() {
    RedisCacheInvalidationPublisher customPublisher =
        new RedisCacheInvalidationPublisher(stringRedisTemplate, "custom:channel");
    customPublisher.publishEvict("key1");
    verify(stringRedisTemplate).convertAndSend(eq("custom:channel"), anyString());
  }

  @Test
  void testPublishEvictHandlesException() {
    org.mockito.Mockito.doThrow(new RuntimeException("Redis connection failed"))
        .when(stringRedisTemplate).convertAndSend(anyString(), anyString());
    // Should not throw
    publisher.publishEvict("user:1");
  }

  @Test
  void testPublishClearHandlesException() {
    org.mockito.Mockito.doThrow(new RuntimeException("Redis connection failed"))
        .when(stringRedisTemplate).convertAndSend(anyString(), anyString());
    // Should not throw
    publisher.publishClear();
  }

  @Test
  void testPublishClearByPrefix() {
    publisher.publishClearByPrefix("user");
    verify(stringRedisTemplate).convertAndSend(eq("bing-cache:invalidation"), anyString());
  }

  @Test
  void testPublishClearByPrefixHandlesException() {
    org.mockito.Mockito.doThrow(new RuntimeException("Redis connection failed"))
        .when(stringRedisTemplate).convertAndSend(anyString(), anyString());
    // Should not throw
    publisher.publishClearByPrefix("user");
  }
}
