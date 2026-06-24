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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 缓存失效通知消息.
 *
 * <p>用于 Redis Pub/Sub 跨实例传递缓存失效事件，
 * 支持单 key 驱逐（EVICT）、全量清除（CLEAR）和按前缀清除（CLEAR_PREFIX）三种类型。</p>
 *
 * <p>序列化方式为 Jackson JSON（{@link #toJson()} / {@link #fromJson(String)}），
 * 不使用 Java 原生序列化。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CacheInvalidationMessage {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
      .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);

  /**
   * 失效消息类型.
   */
  public enum Type {
    /** 单 key 驱逐. */
    EVICT,
    /** 全量清除. */
    CLEAR,
    /** 按前缀清除. */
    CLEAR_PREFIX,
    /** 按分组清除. */
    CLEAR_GROUP
  }

  private Type type;

  private String key;

  private String group;

  private long timestamp;

  private String instanceId;

  /** Jackson 反序列化用. */
  CacheInvalidationMessage() {
  }

  private CacheInvalidationMessage(Type type, String key, long timestamp,
      String instanceId) {
    this.type = type;
    this.key = key;
    this.timestamp = timestamp;
    this.instanceId = instanceId;
  }

  /**
   * 创建单 key 驱逐消息.
   *
   * @param key        需要驱逐的缓存 key
   * @param instanceId 发送实例的唯一标识，用于避免自己处理自己发出的消息
   * @return 驱逐消息
   */
  public static CacheInvalidationMessage evict(String key, String instanceId) {
    return new CacheInvalidationMessage(Type.EVICT, key, System.currentTimeMillis(),
        instanceId);
  }

  /**
   * 创建全量清除消息.
   *
   * @param instanceId 发送实例的唯一标识，用于避免自己处理自己发出的消息
   * @return 清除消息
   */
  public static CacheInvalidationMessage clear(String instanceId) {
    return new CacheInvalidationMessage(Type.CLEAR, null, System.currentTimeMillis(),
        instanceId);
  }

  /**
   * 创建按前缀清除消息.
   *
   * @param prefix     需要清除的缓存 key 前缀
   * @param instanceId 发送实例的唯一标识，用于避免自己处理自己发出的消息
   * @return 按前缀清除消息
   */
  public static CacheInvalidationMessage clearPrefix(String prefix, String instanceId) {
    return new CacheInvalidationMessage(Type.CLEAR_PREFIX, prefix, System.currentTimeMillis(),
        instanceId);
  }

  /**
   * 创建按分组清除消息.
   *
   * @param group      需要清除的缓存分组名称
   * @param instanceId 发送实例的唯一标识，用于避免自己处理自己发出的消息
   * @return 按分组清除消息
   */
  public static CacheInvalidationMessage clearGroup(String group, String instanceId) {
    CacheInvalidationMessage msg = new CacheInvalidationMessage(Type.CLEAR_GROUP, null,
        System.currentTimeMillis(), instanceId);
    msg.group = group;
    return msg;
  }

  /**
   * 将消息序列化为 JSON 字符串.
   *
   * @return JSON 字符串
   */
  public String toJson() {
    try {
      return OBJECT_MAPPER.writeValueAsString(this);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize CacheInvalidationMessage", e);
    }
  }

  /**
   * 从 JSON 字符串反序列化消息.
   *
   * @param json JSON 字符串
   * @return 缓存失效消息
   */
  public static CacheInvalidationMessage fromJson(String json) {
    try {
      return OBJECT_MAPPER.readValue(json, CacheInvalidationMessage.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to deserialize CacheInvalidationMessage", e);
    }
  }

  public Type getType() {
    return type;
  }

  public String getKey() {
    return key;
  }

  public String getGroup() {
    return group;
  }

  public void setGroup(String group) {
    this.group = group;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public String getInstanceId() {
    return instanceId;
  }
}
