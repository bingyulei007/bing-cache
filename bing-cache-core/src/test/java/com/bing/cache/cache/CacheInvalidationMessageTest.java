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

import org.junit.jupiter.api.Test;

/**
 * CacheInvalidationMessage 单元测试.
 */
class CacheInvalidationMessageTest {

  @Test
  void testEvictMessage() {
    CacheInvalidationMessage message = CacheInvalidationMessage.evict("user:1", "instance-1");
    assertEquals(CacheInvalidationMessage.Type.EVICT, message.getType());
    assertEquals("user:1", message.getKey());
    assertEquals("instance-1", message.getInstanceId());
    assertTrue(message.getTimestamp() > 0);
  }

  @Test
  void testClearMessage() {
    CacheInvalidationMessage message = CacheInvalidationMessage.clear("instance-1");
    assertEquals(CacheInvalidationMessage.Type.CLEAR, message.getType());
    assertNull(message.getKey());
    assertEquals("instance-1", message.getInstanceId());
    assertTrue(message.getTimestamp() > 0);
  }

  @Test
  void testEvictMessageJsonRoundTrip() {
    CacheInvalidationMessage original = CacheInvalidationMessage.evict("order:123", "inst-a");
    String json = original.toJson();
    assertNotNull(json);
    assertTrue(json.contains("EVICT"));
    assertTrue(json.contains("order:123"));
    assertTrue(json.contains("inst-a"));

    CacheInvalidationMessage deserialized = CacheInvalidationMessage.fromJson(json);
    assertEquals(original.getType(), deserialized.getType());
    assertEquals(original.getKey(), deserialized.getKey());
    assertEquals(original.getTimestamp(), deserialized.getTimestamp());
    assertEquals(original.getInstanceId(), deserialized.getInstanceId());
  }

  @Test
  void testClearMessageJsonRoundTrip() {
    CacheInvalidationMessage original = CacheInvalidationMessage.clear("inst-b");
    String json = original.toJson();
    assertNotNull(json);
    assertTrue(json.contains("CLEAR"));
    assertTrue(json.contains("inst-b"));

    CacheInvalidationMessage deserialized = CacheInvalidationMessage.fromJson(json);
    assertEquals(original.getType(), deserialized.getType());
    assertNull(deserialized.getKey());
    assertEquals(original.getTimestamp(), deserialized.getTimestamp());
    assertEquals(original.getInstanceId(), deserialized.getInstanceId());
  }

  @Test
  void testEvictMessageJsonFormat() {
    CacheInvalidationMessage message = CacheInvalidationMessage.evict("test:key", "inst-c");
    String json = message.toJson();
    assertTrue(json.startsWith("{"));
    assertTrue(json.contains("\"type\":\"EVICT\""));
    assertTrue(json.contains("\"key\":\"test:key\""));
    assertTrue(json.contains("\"timestamp\""));
    assertTrue(json.contains("\"instanceId\":\"inst-c\""));
  }

  @Test
  void testClearPrefixMessage() {
    CacheInvalidationMessage message = CacheInvalidationMessage.clearPrefix("user", "instance-1");
    assertEquals(CacheInvalidationMessage.Type.CLEAR_PREFIX, message.getType());
    assertEquals("user", message.getKey());
    assertEquals("instance-1", message.getInstanceId());
    assertTrue(message.getTimestamp() > 0);
  }

  @Test
  void testClearPrefixMessageJsonRoundTrip() {
    CacheInvalidationMessage original = CacheInvalidationMessage.clearPrefix("user", "inst-d");
    String json = original.toJson();
    assertNotNull(json);
    assertTrue(json.contains("CLEAR_PREFIX"));
    assertTrue(json.contains("user"));
    assertTrue(json.contains("inst-d"));

    CacheInvalidationMessage deserialized = CacheInvalidationMessage.fromJson(json);
    assertEquals(original.getType(), deserialized.getType());
    assertEquals(original.getKey(), deserialized.getKey());
    assertEquals(original.getTimestamp(), deserialized.getTimestamp());
    assertEquals(original.getInstanceId(), deserialized.getInstanceId());
  }

  private void assertTrue(boolean condition) {
    if (!condition) {
      throw new AssertionError("Expected true but was false");
    }
  }
}
