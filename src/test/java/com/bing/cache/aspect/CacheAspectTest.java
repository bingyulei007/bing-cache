package com.bing.cache.aspect;

import com.bing.cache.annotation.BingCache;
import com.bing.cache.cache.CaffeineCacheManager;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * CacheAspect 单元测试.
 *
 * <p>使用 Spring 上下文模拟 AOP 代理，验证缓存切面行为。</p>
 */
class CacheAspectTest {

  /**
   * 测试首次调用执行方法并缓存结果.
   */
  @Test
  void testFirstCallExecutesMethod() {
    try (AnnotationConfigApplicationContext ctx =
        new AnnotationConfigApplicationContext(TestConfig.class)) {
      TestService service = ctx.getBean(TestService.class);
      TestService target = ctx.getBean("testServiceTarget", TestService.class);

      String result = service.findById(1L);

      assertEquals("user_1", result);
      assertEquals(1, target.getCallCount());
    }
  }

  /**
   * 测试第二次调用走缓存，不执行方法.
   */
  @Test
  void testSecondCallUsesCache() {
    try (AnnotationConfigApplicationContext ctx =
        new AnnotationConfigApplicationContext(TestConfig.class)) {
      TestService service = ctx.getBean(TestService.class);
      TestService target = ctx.getBean("testServiceTarget", TestService.class);

      service.findById(1L);
      service.findById(1L);

      assertEquals(1, target.getCallCount());
    }
  }

  /**
   * 测试不同参数走不同缓存.
   */
  @Test
  void testDifferentArgsDifferentCache() {
    try (AnnotationConfigApplicationContext ctx =
        new AnnotationConfigApplicationContext(TestConfig.class)) {
      TestService service = ctx.getBean(TestService.class);
      TestService target = ctx.getBean("testServiceTarget", TestService.class);

      service.findById(1L);
      service.findById(2L);

      assertEquals(2, target.getCallCount());
    }
  }

  /**
   * 测试返回 null 时不缓存，下次仍执行方法.
   */
  @Test
  void testNullResultNotCached() {
    try (AnnotationConfigApplicationContext ctx =
        new AnnotationConfigApplicationContext(TestConfig.class)) {
      TestService service = ctx.getBean(TestService.class);
      TestService target = ctx.getBean("testServiceTarget", TestService.class);

      assertNull(service.findById(999L));
      assertNull(service.findById(999L));

      assertEquals(2, target.getCallCount());
    }
  }

  /**
   * 测试 cacheNullValue=true 时缓存 null 结果，下次不执行方法.
   */
  @Test
  void testNullResultCachedWhenCacheNullValue() {
    try (AnnotationConfigApplicationContext ctx =
        new AnnotationConfigApplicationContext(TestConfig.class)) {
      TestService service = ctx.getBean(TestService.class);
      TestService target = ctx.getBean("testServiceTarget", TestService.class);

      assertNull(service.findByNameNull("missing"));
      assertNull(service.findByNameNull("missing"));

      assertEquals(1, target.getCallCount());
    }
  }

  /**
   * 测试自定义 keyPrefix.
   */
  @Test
  void testCustomKeyPrefix() {
    try (AnnotationConfigApplicationContext ctx =
        new AnnotationConfigApplicationContext(TestConfig.class)) {
      TestService service = ctx.getBean(TestService.class);
      TestService target = ctx.getBean("testServiceTarget", TestService.class);

      String result = service.findByName("test");

      assertEquals("name_test", result);
      assertEquals(1, target.getCallCount());
    }
  }

  /**
   * 测试自定义 keyPrefix 的缓存命中.
   */
  @Test
  void testCustomKeyPrefixCacheHit() {
    try (AnnotationConfigApplicationContext ctx =
        new AnnotationConfigApplicationContext(TestConfig.class)) {
      TestService service = ctx.getBean(TestService.class);
      TestService target = ctx.getBean("testServiceTarget", TestService.class);

      service.findByName("test");
      service.findByName("test");

      assertEquals(1, target.getCallCount());
    }
  }

  /**
   * 测试配置类.
   */
  @Configuration
  @EnableAspectJAutoProxy
  static class TestConfig {

    @Bean
    public CaffeineCacheManager cacheManager() {
      return new CaffeineCacheManager();
    }

    @Bean
    public CacheAspect cacheAspect(CaffeineCacheManager cacheManager) {
      return new CacheAspect(cacheManager);
    }

    @Bean
    public TestService testServiceTarget() {
      return new TestService();
    }
  }

  /**
   * 测试用的 Service 类.
   */
  static class TestService {

    private int callCount = 0;

    /**
     * 根据 ID 查询，模拟数据库查询.
     *
     * @param id 用户 ID
     * @return 用户名
     */
    @BingCache(expireTime = 60)
    public String findById(Long id) {
      callCount++;
      if (id == 999L) {
        return null;
      }
      return "user_" + id;
    }

    /**
     * 根据名称查询，使用自定义 keyPrefix.
     *
     * @param name 名称
     * @return 结果
     */
    @BingCache(keyPrefix = "user:name", expireTime = 30)
    public String findByName(String name) {
      callCount++;
      return "name_" + name;
    }

    /**
     * 根据名称查询，cacheNullValue=true.
     *
     * @param name 名称
     * @return 结果
     */
    @BingCache(keyPrefix = "user:name:null", expireTime = 30, cacheNullValue = true)
    public String findByNameNull(String name) {
      callCount++;
      return null;
    }

    public int getCallCount() {
      return callCount;
    }
  }
}
