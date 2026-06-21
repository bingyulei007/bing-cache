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

import com.bing.cache.aspect.CacheAspect;
import com.bing.cache.aspect.CacheEvictAspect;
import com.bing.cache.cache.CacheInvalidationListener;
import com.bing.cache.cache.CacheInvalidationMessage;
import com.bing.cache.cache.CacheManager;
import com.bing.cache.cache.CaffeineCacheManager;
import com.bing.cache.cache.CompositeCacheManager;
import com.bing.cache.cache.CacheVersionStore;
import com.bing.cache.cache.RedisCacheManager;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BingCacheAutoConfiguration 单元测试.
 */
class BingCacheAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(BingCacheAutoConfiguration.class));

  /**
   * 测试自动配置注册默认的 CacheManager 和 CacheAspect.
   */
  @Test
  void testDefaultBeansRegistered() {
    contextRunner.run(context -> {
      assertTrue(context.containsBean("cacheManager"));
      assertTrue(context.containsBean("cacheAspect"));
      assertTrue(context.containsBean("cacheEvictAspect"));
      assertNotNull(context.getBean(CacheManager.class));
      assertNotNull(context.getBean(CacheAspect.class));
      assertNotNull(context.getBean(CacheEvictAspect.class));
      assertTrue(context.getBean(CacheManager.class) instanceof CaffeineCacheManager);
    });
  }

  /**
   * 测试用户自定义 CacheManager 不被覆盖.
   */
  @Test
  void testCustomCacheManagerNotOverridden() {
    contextRunner
        .withBean("cacheManager", CacheManager.class, CaffeineCacheManager::new)
        .run(context -> {
          assertTrue(context.getBeansOfType(CacheManager.class).size() == 1);
        });
  }

  /**
   * 测试 L1+L2 模式下 l1-max-ttl=0 时自动兜底为 DEFAULT_L1_MAX_TTL_SECONDS（300s）.
   *
   * <p>原因：L1+L2 模式下单 key evict 的 Pub/Sub 丢失无法通过对账补偿，
   * 需要 l1-max-ttl 兜底，否则脏数据会无限期驻留 L1。</p>
   */
  @Test
  void testL1MaxTtlDefaultsTo300InCompositeMode() {
    BingCacheAutoConfiguration config = new BingCacheAutoConfiguration();
    BingCacheProperties props = new BingCacheProperties();
    // 默认 l1MaxTtl=0，应自动兜底为 300
    assertEquals(0L, props.getCaffeine().getL1MaxTtl());

    @SuppressWarnings("unchecked")
    ObjectProvider<CacheVersionStore> provider = Mockito.mock(ObjectProvider.class);
    Mockito.when(provider.getIfAvailable()).thenReturn(null);

    CacheManager cm = config.compositeCacheManager(
        Mockito.mock(StringRedisTemplate.class),
        Mockito.mock(RedisTemplate.class),
        props,
        "test-instance",
        provider);

    assertTrue(cm instanceof CompositeCacheManager);
    CaffeineCacheManager l1 = (CaffeineCacheManager) ((CompositeCacheManager) cm).getL1CacheManager();
    assertEquals(BingCacheAutoConfiguration.DEFAULT_L1_MAX_TTL_SECONDS,
        l1.getL1MaxTtlSeconds());
  }

  /**
   * 测试 L1+L2 模式下用户显式设置 l1-max-ttl 时尊重用户值，不自动兜底.
   */
  @Test
  void testCustomL1MaxTtlRespectedInCompositeMode() {
    BingCacheAutoConfiguration config = new BingCacheAutoConfiguration();
    BingCacheProperties props = new BingCacheProperties();
    props.getCaffeine().setL1MaxTtl(600L);

    @SuppressWarnings("unchecked")
    ObjectProvider<CacheVersionStore> provider = Mockito.mock(ObjectProvider.class);
    Mockito.when(provider.getIfAvailable()).thenReturn(null);

    CacheManager cm = config.compositeCacheManager(
        Mockito.mock(StringRedisTemplate.class),
        Mockito.mock(RedisTemplate.class),
        props,
        "test-instance",
        provider);

    CaffeineCacheManager l1 = (CaffeineCacheManager) ((CompositeCacheManager) cm).getL1CacheManager();
    assertEquals(600L, l1.getL1MaxTtlSeconds());
  }

  /**
   * 测试纯 L1 模式下 l1-max-ttl=0 保持不限制，不自动兜底.
   */
  @Test
  void testL1MaxTtlStaysZeroInPureL1Mode() {
    BingCacheAutoConfiguration config = new BingCacheAutoConfiguration();
    BingCacheProperties props = new BingCacheProperties();

    CacheManager cm = config.caffeineCacheManager(props);

    assertTrue(cm instanceof CaffeineCacheManager);
    assertEquals(0L, ((CaffeineCacheManager) cm).getL1MaxTtlSeconds());
  }

  /**
   * 测试 Redis Pub/Sub 监听适配器已完成初始化，收到消息时不会因 invoker 为空抛出 NPE.
   */
  @Test
  void testCacheInvalidationMessageListenerAdapterInitialized() {
    BingCacheAutoConfiguration.RedisConfiguration config =
        new BingCacheAutoConfiguration.RedisConfiguration();
    CacheInvalidationListener listener = new CacheInvalidationListener(
        Mockito.mock(CacheManager.class), "self-instance");

    MessageListenerAdapter adapter =
        config.cacheInvalidationMessageListenerAdapter(listener);
    byte[] body = CacheInvalidationMessage.clear("self-instance")
        .toJson().getBytes(StandardCharsets.UTF_8);

    assertDoesNotThrow(() -> adapter.onMessage(
        new DefaultMessage("demo-channel".getBytes(StandardCharsets.UTF_8), body), null));
  }

  /**
   * 测试 Redis 性能相关配置属性正确注入到 RedisCacheManager.
   *
   * <p>验证 scanCount、deleteBatchSize、useUnlink、failureLogInterval 四个属性
   * 从 BingCacheProperties 正确传递到 RedisCacheManager 实例。</p>
   */
  @Test
  void testRedisPerformancePropertiesInjected() {
    BingCacheAutoConfiguration config = new BingCacheAutoConfiguration();
    BingCacheProperties props = new BingCacheProperties();
    // 设置自定义性能属性
    props.getRedis().setScanCount(2000L);
    props.getRedis().setDeleteBatchSize(250L);
    props.getRedis().setUseUnlink(false);
    props.getRedis().setFailureLogInterval(60L);

    @SuppressWarnings("unchecked")
    ObjectProvider<CacheVersionStore> provider = Mockito.mock(ObjectProvider.class);
    Mockito.when(provider.getIfAvailable()).thenReturn(null);

    CacheManager cm = config.compositeCacheManager(
        Mockito.mock(StringRedisTemplate.class),
        Mockito.mock(RedisTemplate.class),
        props,
        "test-instance",
        provider);

    assertTrue(cm instanceof CompositeCacheManager);
    RedisCacheManager l2 = ((CompositeCacheManager) cm).getL2CacheManager();

    assertEquals(2000L, l2.getScanCount());
    assertEquals(250L, l2.getDeleteBatchSize());
    assertEquals(false, l2.isUseUnlink());
    assertEquals(60L, l2.getFailureLogInterval());
  }

  /**
   * 测试默认 BingCacheProperties 传递给 RedisCacheManager 时使用正确的默认值.
   *
   * <p>默认值应为：scanCount=1000, deleteBatchSize=500, useUnlink=true, failureLogInterval=30</p>
   */
  @Test
  void testRedisPerformancePropertiesDefaults() {
    BingCacheAutoConfiguration config = new BingCacheAutoConfiguration();
    BingCacheProperties props = new BingCacheProperties();
    // 使用默认配置，不设置任何自定义值

    @SuppressWarnings("unchecked")
    ObjectProvider<CacheVersionStore> provider = Mockito.mock(ObjectProvider.class);
    Mockito.when(provider.getIfAvailable()).thenReturn(null);

    CacheManager cm = config.compositeCacheManager(
        Mockito.mock(StringRedisTemplate.class),
        Mockito.mock(RedisTemplate.class),
        props,
        "test-instance",
        provider);

    assertTrue(cm instanceof CompositeCacheManager);
    RedisCacheManager l2 = ((CompositeCacheManager) cm).getL2CacheManager();

    assertEquals(1000L, l2.getScanCount());
    assertEquals(500L, l2.getDeleteBatchSize());
    assertEquals(true, l2.isUseUnlink());
    assertEquals(30L, l2.getFailureLogInterval());
  }
}
