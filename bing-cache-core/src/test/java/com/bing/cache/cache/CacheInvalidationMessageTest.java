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

  @Test
  void testClearGroupMessage() {
    CacheInvalidationMessage message = CacheInvalidationMessage.clearGroup("user", "instance-1");
    assertEquals(CacheInvalidationMessage.Type.CLEAR_GROUP, message.getType());
    assertEquals("user", message.getGroup());
    assertNull(message.getKey(), "CLEAR_GROUP message should not populate key field");
    assertEquals("instance-1", message.getInstanceId());
    assertTrue(message.getTimestamp() > 0);
  }

  @Test
  void testClearGroupMessageJsonRoundTrip() {
    CacheInvalidationMessage original = CacheInvalidationMessage.clearGroup("user", "inst-e");
    String json = original.toJson();
    assertNotNull(json);
    assertTrue(json.contains("CLEAR_GROUP"));
    assertTrue(json.contains("\"group\":\"user\""));
    assertTrue(json.contains("inst-e"));

    CacheInvalidationMessage deserialized = CacheInvalidationMessage.fromJson(json);
    assertEquals(original.getType(), deserialized.getType());
    assertEquals(original.getGroup(), deserialized.getGroup());
    assertNull(deserialized.getKey(), "CLEAR_GROUP key field should remain null after round-trip");
    assertEquals(original.getTimestamp(), deserialized.getTimestamp());
    assertEquals(original.getInstanceId(), deserialized.getInstanceId());
  }

  /**
   * 测试未知 type 反序列化为 null（滚动升级降级）.
   *
   * <p>OBJECT_MAPPER 启用了 READ_UNKNOWN_ENUM_VALUES_AS_NULL，
   * 旧实例收到新版本发送的未知 Type 时应反序列化为 null，
   * 而非抛 InvalidFormatException。CacheInvalidationListener 会走 type == null → WARN 分支，
   * 不会输出 ERROR + 堆栈。</p>
   */
  @Test
  void testUnknownTypeDeserializesToNull() {
    // 模拟未来版本发送的未知 type：手造 JSON，type 字段使用不存在的枚举值
    String jsonFromFutureVersion = "{\"type\":\"CLEAR_GROUP2\",\"group\":\"user\","
        + "\"timestamp\":1234567890,\"instanceId\":\"future-inst\"}";
    CacheInvalidationMessage deserialized = CacheInvalidationMessage.fromJson(jsonFromFutureVersion);
    assertNull(deserialized.getType(), "Unknown enum value should deserialize to null");
    assertEquals("user", deserialized.getGroup());
    assertEquals("future-inst", deserialized.getInstanceId());
  }

  private void assertTrue(boolean condition) {
    if (!condition) {
      throw new AssertionError("Expected true but was false");
    }
  }
}
