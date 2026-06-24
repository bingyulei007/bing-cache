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

import java.util.Objects;

/**
 * 缓存失效通知监听器.
 *
 * <p>接收 Redis Pub/Sub 广播的缓存失效消息，
 * 并对本地 L1 (Caffeine) 缓存执行相应的驱逐或清除操作。</p>
 */
public class CacheInvalidationListener {

  private static final Logger LOG = LoggerFactory.getLogger(CacheInvalidationListener.class);

  private final CacheManager l1CacheManager;

  private final String instanceId;

  /**
   * 构造方法.
   *
   * @param l1CacheManager 本地 L1 缓存管理器
   * @param instanceId     当前实例的唯一标识，用于过滤自己发出的消息
   */
  public CacheInvalidationListener(CacheManager l1CacheManager,
      String instanceId) {
    this.l1CacheManager = Objects.requireNonNull(l1CacheManager, "l1CacheManager cannot be null");
    this.instanceId = instanceId; // nullable, 用于过滤自己发出的消息
  }

  /**
   * 处理接收到的缓存失效消息.
   *
   * <p>由 {@link org.springframework.data.redis.listener.adapter.MessageListenerAdapter}
   * 通过反射调用，方法名需与适配器配置一致。</p>
   *
   * <p>过滤掉自己实例发出的消息，避免重复清除本地缓存。</p>
   *
   * @param messageJson 消息 JSON 字符串
   */
  public void handleMessage(String messageJson) {
    try {
      CacheInvalidationMessage message = CacheInvalidationMessage.fromJson(messageJson);
      if (message.getType() == null) {
        LOG.warn("Invalid cache invalidation message, type is null: {}", messageJson);
        return;
      }
      // 仅当双方 instanceId 都非 null 且相等时才视为自身消息。
      // 若消息 instanceId 为 null（异常构造），不应误判为自身消息而跳过。
      if (instanceId != null && instanceId.equals(message.getInstanceId())) {
        LOG.debug("Ignoring self-published cache invalidation: type={}, key={}",
            message.getType(), message.getKey());
        return;
      }
      switch (message.getType()) {
        case EVICT:
          if (message.getKey() == null) {
            LOG.warn("Ignoring EVICT message with null key: {}", messageJson);
            return;
          }
          l1CacheManager.evict(message.getKey());
          LOG.debug("Received cache invalidation: EVICT key={}", message.getKey());
          break;
        case CLEAR:
          l1CacheManager.clear();
          LOG.debug("Received cache invalidation: CLEAR");
          break;
        case CLEAR_PREFIX:
          if (message.getKey() == null) {
            LOG.warn("Ignoring CLEAR_PREFIX message with null key: {}", messageJson);
            return;
          }
          l1CacheManager.clearByPrefix(message.getKey());
          LOG.debug("Received cache invalidation: CLEAR_PREFIX prefix={}", message.getKey());
          break;
        case CLEAR_GROUP:
          if (message.getGroup() == null) {
            LOG.warn("Ignoring CLEAR_GROUP message with null group: {}", messageJson);
            return;
          }
          l1CacheManager.clearByGroup(message.getGroup());
          LOG.debug("Received cache invalidation: CLEAR_GROUP group={}", message.getGroup());
          break;
        default:
          LOG.warn("Unknown cache invalidation type: {}", message.getType());
          break;
      }
    } catch (Exception e) {
      LOG.error("Failed to process cache invalidation message: {}", messageJson, e);
    }
  }
}
