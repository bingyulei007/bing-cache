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
