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

package com.bing.cache.config;

import com.bing.cache.cache.CacheManager;
import com.bing.cache.cache.CompositeCacheManager;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.LifecycleProcessor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 L1+L2 模式下 Pub/Sub 监听相关 bean 是否真的被注册.
 *
 * <p>该测试复现多实例场景中的问题：主 {@code cacheManager} 已经是
 * {@link CompositeCacheManager}，但用于订阅 Redis Pub/Sub 的 listener/container
 * 没有注册，导致其他实例的 L1 不会收到失效通知。</p>
 */
class CompositeModeBeanRegistrationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(BingCacheAutoConfiguration.class))
      // 提供最小化的 Redis 相关 bean，激活 RedisConfiguration 和 CompositeCacheManager 分支
      .withBean(RedisConnectionFactory.class, () -> {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.isSubscribed()).thenReturn(false);
        return connectionFactory;
      })
      // 只验证 bean 注册，不在 mock RedisConnection 上启动真实订阅线程
      .withBean("lifecycleProcessor", LifecycleProcessor.class, () -> mock(LifecycleProcessor.class))
      .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
      // 关闭对账，避免 SmartLifecycle 在 mock 上启动产生噪声
      .withPropertyValues("bing.cache.reconciliation.enabled=false");

  @Test
  void compositeModeBeansRegistered() {
    contextRunner.run(context -> {
      // 前置：确认进入了 L1+L2 模式，cacheManager 实际是 CompositeCacheManager
      assertThat(context.getBeanNamesForType(CompositeCacheManager.class))
          .as("CompositeCacheManager 应以 cacheManager bean 暴露")
          .containsExactly("cacheManager");
      assertThat(context.getBean(CacheManager.class))
          .as("应进入 L1+L2 模式，cacheManager 实际类型为 CompositeCacheManager")
          .isInstanceOf(CompositeCacheManager.class);

      // 决定性断言：以下 bean 的条件都是 @ConditionalOnBean(CompositeCacheManager.class)
      assertThat(context.containsBean("cacheInvalidationListener"))
          .as("CacheInvalidationListener 必须在 L1+L2 模式下注册（否则 Pub/Sub 收不到消息）")
          .isTrue();
      assertThat(context.containsBean("bingCacheInvalidationMessageListenerAdapter"))
          .as("MessageListenerAdapter 必须注册")
          .isTrue();
      assertThat(context.containsBean("bingCacheInvalidationListenerContainer"))
          .as("RedisMessageListenerContainer 必须注册（否则没有订阅者）")
          .isTrue();
    });
  }
}
