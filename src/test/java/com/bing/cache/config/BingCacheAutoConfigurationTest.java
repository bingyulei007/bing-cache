package com.bing.cache.config;

import com.bing.cache.aspect.CacheAspect;
import com.bing.cache.aspect.CacheEvictAspect;
import com.bing.cache.cache.CacheManager;
import com.bing.cache.cache.CaffeineCacheManager;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
}
