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

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Objects;
import java.util.UUID;

/**
 * 基于 Redis Pub/Sub 的缓存失效通知发布实现.
 *
 * <p>通过 Redis 的 publish/subscribe 机制向其他实例广播缓存失效事件，
 * 确保多实例部署时本地缓存的一致性。</p>
 */
public class RedisCacheInvalidationPublisher implements CacheInvalidationPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(RedisCacheInvalidationPublisher.class);

  private final StringRedisTemplate stringRedisTemplate;

  private final String channelName;

  private final String instanceId;

  /**
   * 构造方法.
   *
   * @param stringRedisTemplate Redis 字符串操作模板
   * @param channelName         Pub/Sub 频道名称
   * @param instanceId          当前实例的唯一标识，用于避免自己处理自己发出的消息
   */
  public RedisCacheInvalidationPublisher(StringRedisTemplate stringRedisTemplate,
      String channelName, String instanceId) {
    this.stringRedisTemplate = Objects.requireNonNull(stringRedisTemplate, "stringRedisTemplate cannot be null");
    this.channelName = Objects.requireNonNull(channelName, "channelName cannot be null");
    this.instanceId = Objects.requireNonNull(instanceId, "instanceId cannot be null");
  }

  /**
   * 构造方法（自动生成实例ID）.
   *
   * @param stringRedisTemplate Redis 字符串操作模板
   * @param channelName         Pub/Sub 频道名称
   */
  public RedisCacheInvalidationPublisher(StringRedisTemplate stringRedisTemplate,
      String channelName) {
    this(stringRedisTemplate, channelName, UUID.randomUUID().toString());
  }

  @Override
  public void publishEvict(String key) {
    CacheInvalidationMessage message = CacheInvalidationMessage.evict(key, instanceId);
    publish(message);
  }

  @Override
  public void publishClear() {
    CacheInvalidationMessage message = CacheInvalidationMessage.clear(instanceId);
    publish(message);
  }

  @Override
  public void publishClearByPrefix(String prefix) {
    CacheInvalidationMessage message = CacheInvalidationMessage.clearPrefix(prefix, instanceId);
    publish(message);
  }

  @Override
  public void publishClearByGroup(String group) {
    CacheInvalidationMessage message = CacheInvalidationMessage.clearGroup(group, instanceId);
    publish(message);
  }

  private void publish(CacheInvalidationMessage message) {
    try {
      String json = message.toJson();
      stringRedisTemplate.convertAndSend(channelName, json);
      LOG.debug("Published cache invalidation message: type={}, key={}, group={}",
          message.getType(), message.getKey(), message.getGroup());
    } catch (Exception e) {
      LOG.error("Failed to publish cache invalidation message: type={}, key={}, group={}",
          message.getType(), message.getKey(), message.getGroup(), e);
    }
  }
}
