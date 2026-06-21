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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * BingCacheProperties 单元测试.
 */
class BingCachePropertiesTest {

  @Test
  void testDefaultValues() {
    BingCacheProperties properties = new BingCacheProperties();
    assertNotNull(properties.getCaffeine());
    assertNotNull(properties.getRedis());
    assertNotNull(properties.getReconciliation());
    assertEquals(1000L, properties.getCaffeine().getMaxSize());
    assertEquals(0L, properties.getCaffeine().getL1MaxTtl());
    assertTrue(properties.getRedis().isEnabled());
    assertEquals("bing-cache:", properties.getRedis().getKeyPrefix());
    assertEquals("bing-cache:invalidation", properties.getRedis().getChannelName());
    assertEquals(1000L, properties.getRedis().getScanCount());
    assertEquals(500L, properties.getRedis().getDeleteBatchSize());
    assertTrue(properties.getRedis().isUseUnlink());
    assertEquals(30L, properties.getRedis().getFailureLogInterval());
    assertTrue(properties.getReconciliation().isEnabled());
    assertEquals(30L, properties.getReconciliation().getInterval());
  }

  @Test
  void testCustomCaffeineMaxSize() {
    BingCacheProperties properties = new BingCacheProperties();
    properties.getCaffeine().setMaxSize(2000L);
    assertEquals(2000L, properties.getCaffeine().getMaxSize());
  }

  @Test
  void testCustomL1MaxTtl() {
    BingCacheProperties properties = new BingCacheProperties();
    properties.getCaffeine().setL1MaxTtl(300L);
    assertEquals(300L, properties.getCaffeine().getL1MaxTtl());
  }

  @Test
  void testCustomRedisConfig() {
    BingCacheProperties properties = new BingCacheProperties();
    properties.getRedis().setEnabled(false);
    properties.getRedis().setKeyPrefix("myapp:");
    properties.getRedis().setChannelName("myapp:cache:invalidation");
    assertFalse(properties.getRedis().isEnabled());
    assertEquals("myapp:", properties.getRedis().getKeyPrefix());
    assertEquals("myapp:cache:invalidation", properties.getRedis().getChannelName());
  }

  /**
   * 验证 Redis 性能配置项通过 Spring Boot 属性绑定正确加载.
   */
  @Test
  void testRedisPerformancePropertyBinding() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BingCacheAutoConfiguration.class))
        .withPropertyValues(
            "bing.cache.redis.scan-count=2000",
            "bing.cache.redis.delete-batch-size=250",
            "bing.cache.redis.use-unlink=false",
            "bing.cache.redis.failure-log-interval=60")
        .run(context -> {
          BingCacheProperties properties = context.getBean(BingCacheProperties.class);
          assertEquals(2000L, properties.getRedis().getScanCount());
          assertEquals(250L, properties.getRedis().getDeleteBatchSize());
          assertFalse(properties.getRedis().isUseUnlink());
          assertEquals(60L, properties.getRedis().getFailureLogInterval());
        });
  }

  @Test
  void testCustomReconciliationConfig() {
    BingCacheProperties properties = new BingCacheProperties();
    properties.getReconciliation().setEnabled(false);
    properties.getReconciliation().setInterval(60L);
    assertFalse(properties.getReconciliation().isEnabled());
    assertEquals(60L, properties.getReconciliation().getInterval());
  }

  /**
   * 验证 reconciliation.interval=0 触发校验失败，启动报错.
   *
   * <p>interval 必须为正整数，否则 scheduleAtFixedRate 会抛 IllegalArgumentException。
   * 校验注解应在绑定阶段提前拦截，给出清晰错误而非启动时栈。</p>
   */
  @Test
  void testInvalidIntervalFailsValidation() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BingCacheAutoConfiguration.class))
        .withPropertyValues("bing.cache.reconciliation.interval=0")
        .run(context -> {
          assertNotNull(context.getStartupFailure(),
              "Expected startup failure due to invalid interval=0");
        });
  }

  /**
   * 验证 caffeine.max-size=0 触发校验失败.
   */
  @Test
  void testInvalidMaxSizeFailsValidation() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BingCacheAutoConfiguration.class))
        .withPropertyValues("bing.cache.caffeine.max-size=0")
        .run(context -> {
          assertNotNull(context.getStartupFailure(),
              "Expected startup failure due to invalid max-size=0");
        });
  }

  /**
   * 验证 redis.scan-count=0 触发校验失败.
   */
  @Test
  void testInvalidScanCountFailsValidation() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BingCacheAutoConfiguration.class))
        .withPropertyValues("bing.cache.redis.scan-count=0")
        .run(context -> {
          assertNotNull(context.getStartupFailure(),
              "Expected startup failure due to invalid scan-count=0");
        });
  }

  /**
   * 验证 redis.delete-batch-size=0 触发校验失败.
   */
  @Test
  void testInvalidDeleteBatchSizeFailsValidation() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BingCacheAutoConfiguration.class))
        .withPropertyValues("bing.cache.redis.delete-batch-size=0")
        .run(context -> {
          assertNotNull(context.getStartupFailure(),
              "Expected startup failure due to invalid delete-batch-size=0");
        });
  }

  /**
   * 验证 redis.failure-log-interval=0 触发校验失败.
   */
  @Test
  void testInvalidFailureLogIntervalFailsValidation() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BingCacheAutoConfiguration.class))
        .withPropertyValues("bing.cache.redis.failure-log-interval=0")
        .run(context -> {
          assertNotNull(context.getStartupFailure(),
              "Expected startup failure due to invalid failure-log-interval=0");
        });
  }

  /**
   * 验证 redis.scan-count=100001 超过 @Max 阈值触发校验失败.
   */
  @Test
  void testExcessiveScanCountFailsValidation() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BingCacheAutoConfiguration.class))
        .withPropertyValues("bing.cache.redis.scan-count=100001")
        .run(context -> {
          assertNotNull(context.getStartupFailure(),
              "Expected startup failure due to excessive scan-count=100001");
        });
  }

  /**
   * 验证 redis.delete-batch-size=10001 超过 @Max 阈值触发校验失败.
   */
  @Test
  void testExcessiveDeleteBatchSizeFailsValidation() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BingCacheAutoConfiguration.class))
        .withPropertyValues("bing.cache.redis.delete-batch-size=10001")
        .run(context -> {
          assertNotNull(context.getStartupFailure(),
              "Expected startup failure due to excessive delete-batch-size=10001");
        });
  }

  /**
   * 验证 redis.failure-log-interval=86401 超过 @Max 阈值触发校验失败.
   */
  @Test
  void testExcessiveFailureLogIntervalFailsValidation() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BingCacheAutoConfiguration.class))
        .withPropertyValues("bing.cache.redis.failure-log-interval=86401")
        .run(context -> {
          assertNotNull(context.getStartupFailure(),
              "Expected startup failure due to excessive failure-log-interval=86401");
        });
  }
}
