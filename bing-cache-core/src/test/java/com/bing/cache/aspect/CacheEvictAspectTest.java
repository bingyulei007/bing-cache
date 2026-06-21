package com.bing.cache.aspect;

import com.bing.cache.annotation.BingCache;
import com.bing.cache.annotation.BingCacheEvict;
import com.bing.cache.cache.CaffeineCacheManager;
import com.bing.cache.util.CacheKeyGenerator;

import org.junit.jupiter.api.Test;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * CacheEvictAspect 单元测试.
 *
 * <p>使用 Spring 上下文模拟 AOP 代理，验证缓存清除切面行为。</p>
 */
class CacheEvictAspectTest {

  /**
   * 测试默认行为：方法执行后清除指定 key 的缓存.
   */
  @Test
  void testEvictAfterInvocation() {
    try (AnnotationConfigApplicationContext ctx =
        new AnnotationConfigApplicationContext(TestConfig.class)) {
      TestService service = ctx.getBean(TestService.class);
      CaffeineCacheManager cacheManager = ctx.getBean(CaffeineCacheManager.class);

      // 先缓存数据
      service.findById(1L);
      assertEquals("user_1", cacheManager.get(
          "com.bing.cache.aspect.CacheEvictAspectTest$TestService.findById([1])"));

      // 执行清除方法后，缓存应被移除
      service.updateUser(1L);
      assertNull(cacheManager.get(
          "com.bing.cache.aspect.CacheEvictAspectTest$TestService.findById([1])"));
    }
  }

  /**
   * 测试 beforeInvocation=true：先清缓存再执行方法.
   */
  @Test
  void testEvictBeforeInvocation() {
    try (AnnotationConfigApplicationContext ctx =
        new AnnotationConfigApplicationContext(TestConfig.class)) {
      TestService service = ctx.getBean(TestService.class);
      CaffeineCacheManager cacheManager = ctx.getBean(CaffeineCacheManager.class);

      // 先缓存数据
      service.findById(1L);
      String cacheKey =
          "com.bing.cache.aspect.CacheEvictAspectTest$TestService.findById([1])";
      assertEquals("user_1", cacheManager.get(cacheKey));

      // beforeInvocation 清除
      service.deleteUserBefore(1L);
      assertNull(cacheManager.get(cacheKey));
    }
  }

  /**
   * 测试默认行为：方法抛异常时不清除缓存.
   */
  @Test
  void testEvictNotTriggeredOnError() {
    try (AnnotationConfigApplicationContext ctx =
        new AnnotationConfigApplicationContext(TestConfig.class)) {
      TestService service = ctx.getBean(TestService.class);
      CaffeineCacheManager cacheManager = ctx.getBean(CaffeineCacheManager.class);

      // 先缓存数据
      service.findById(1L);
      String cacheKey =
          "com.bing.cache.aspect.CacheEvictAspectTest$TestService.findById([1])";
      assertEquals("user_1", cacheManager.get(cacheKey));

      // 方法抛异常，缓存不应被清除
      try {
        service.updateUserWithError(1L);
      } catch (RuntimeException expected) {
        // expected
      }
      assertEquals("user_1", cacheManager.get(cacheKey));
    }
  }

  /**
   * 测试 beforeInvocation=true：方法抛异常，缓存仍被清除.
   */
  @Test
  void testEvictTriggeredBeforeError() {
    try (AnnotationConfigApplicationContext ctx =
        new AnnotationConfigApplicationContext(TestConfig.class)) {
      TestService service = ctx.getBean(TestService.class);
      CaffeineCacheManager cacheManager = ctx.getBean(CaffeineCacheManager.class);

      // 先缓存数据
      service.findById(1L);
      String cacheKey =
          "com.bing.cache.aspect.CacheEvictAspectTest$TestService.findById([1])";
      assertEquals("user_1", cacheManager.get(cacheKey));

      // beforeInvocation=true，即使方法抛异常缓存也被清除
      try {
        service.deleteUserBeforeWithError(1L);
      } catch (RuntimeException expected) {
        // expected
      }
      assertNull(cacheManager.get(cacheKey));
    }
  }

  /**
   * 测试 allEntries=true：清空所有缓存.
   */
  @Test
  void testEvictAllEntries() {
    try (AnnotationConfigApplicationContext ctx =
        new AnnotationConfigApplicationContext(TestConfig.class)) {
      TestService service = ctx.getBean(TestService.class);
      CaffeineCacheManager cacheManager = ctx.getBean(CaffeineCacheManager.class);

      // 缓存多条数据
      service.findById(1L);
      service.findById(2L);
      String key1 =
          "com.bing.cache.aspect.CacheEvictAspectTest$TestService.findById([1])";
      String key2 =
          "com.bing.cache.aspect.CacheEvictAspectTest$TestService.findById([2])";
      assertEquals("user_1", cacheManager.get(key1));
      assertEquals("user_2", cacheManager.get(key2));

      // allEntries 清空所有
      service.clearAll();
      assertNull(cacheManager.get(key1));
      assertNull(cacheManager.get(key2));
    }
  }

  /**
   * 测试 argIndexes 生效：只使用指定参数生成 key.
   */
  @Test
  void testEvictSpecificKeyWithArgIndexes() {
    try (AnnotationConfigApplicationContext ctx =
        new AnnotationConfigApplicationContext(TestConfig.class)) {
      TestService service = ctx.getBean(TestService.class);
      CaffeineCacheManager cacheManager = ctx.getBean(CaffeineCacheManager.class);

      // 缓存数据
      service.findById(1L);
      String cacheKey =
          "com.bing.cache.aspect.CacheEvictAspectTest$TestService.findById([1])";
      assertEquals("user_1", cacheManager.get(cacheKey));

      // 使用 argIndexes={0} 的 evict 方法
      service.updateUserWithArgIndexes(1L, "updated");
      assertNull(cacheManager.get(cacheKey));
    }
  }

  /**
   * 测试 cacheName 匹配：不同方法名通过 cacheName 共享 key.
   */
  @Test
  void testEvictWithCacheName() {
    try (AnnotationConfigApplicationContext ctx =
        new AnnotationConfigApplicationContext(TestConfig.class)) {
      TestService service = ctx.getBean(TestService.class);
      CaffeineCacheManager cacheManager = ctx.getBean(CaffeineCacheManager.class);

      // 使用 cacheName="user" 的 @BingCache 缓存数据
      service.findByCacheName(1L);
      String cacheKey = "user([1])";
      assertEquals("user_1", cacheManager.get(cacheKey));

      // 使用 cacheName="user" 的 @BingCacheEvict 清除
      service.updateUserByCacheName(1L);
      assertNull(cacheManager.get(cacheKey));
    }
  }

  /**
   * 测试 allEntries=true + cacheName：只清除指定 cacheName 的缓存.
   */
  @Test
  void testEvictAllEntriesWithCacheName() {
    try (AnnotationConfigApplicationContext ctx =
        new AnnotationConfigApplicationContext(TestConfig.class)) {
      TestService service = ctx.getBean(TestService.class);
      CaffeineCacheManager cacheManager = ctx.getBean(CaffeineCacheManager.class);

      // 缓存 user 和 dict 两种数据
      service.findByCacheName(1L);
      service.findDict("config");
      String userKey = "user([1])";
      String dictKey = "dict([config])";
      assertEquals("user_1", cacheManager.get(userKey));
      assertEquals("dict_config", cacheManager.get(dictKey));

      // 只清除 user 缓存
      service.clearAllUsers();
      assertNull(cacheManager.get(userKey));
      // dict 缓存不受影响
      assertEquals("dict_config", cacheManager.get(dictKey));
    }
  }

  /**
   * 测试 allEntries=true 无 cacheName：清空所有缓存（向后兼容）.
   */
  @Test
  void testEvictAllEntriesWithoutCacheName() {
    try (AnnotationConfigApplicationContext ctx =
        new AnnotationConfigApplicationContext(TestConfig.class)) {
      TestService service = ctx.getBean(TestService.class);
      CaffeineCacheManager cacheManager = ctx.getBean(CaffeineCacheManager.class);

      // 缓存 user 和 dict 两种数据
      service.findByCacheName(1L);
      service.findDict("config");
      String userKey = "user([1])";
      String dictKey = "dict([config])";
      assertEquals("user_1", cacheManager.get(userKey));
      assertEquals("dict_config", cacheManager.get(dictKey));

      // 无 cacheName 的 allEntries 清空所有缓存
      service.clearAllNoCacheName();
      assertNull(cacheManager.get(userKey));
      assertNull(cacheManager.get(dictKey));
    }
  }

  /**
   * 测试 SpEL key：@BingCache 缓存后，@BingCacheEvict 用相同 SpEL key 清除.
   */
  @Test
  void testEvictWithSpelKey() {
    try (AnnotationConfigApplicationContext ctx =
        new AnnotationConfigApplicationContext(TestConfig.class)) {
      TestService service = ctx.getBean(TestService.class);
      CaffeineCacheManager cacheManager = ctx.getBean(CaffeineCacheManager.class);

      // 使用 SpEL key 缓存
      service.findBySpelKey(1L);
      String cacheKey = "spelCache(1)";
      assertEquals("user_1", cacheManager.get(cacheKey));

      // 使用相同 SpEL key 清除
      service.updateBySpelKey(1L);
      assertNull(cacheManager.get(cacheKey));
    }
  }

  /**
   * 测试 SpEL key 选取参数属性进行 evict.
   */
  @Test
  void testEvictWithSpelKeyByProperty() {
    try (AnnotationConfigApplicationContext ctx =
        new AnnotationConfigApplicationContext(TestConfig.class)) {
      TestService service = ctx.getBean(TestService.class);
      CaffeineCacheManager cacheManager = ctx.getBean(CaffeineCacheManager.class);

      SpelTestUser user = new SpelTestUser(99L, "Alice");
      service.findBySpelUser(user);
      String cacheKey = "spelUser(99)";
      assertEquals("user_99", cacheManager.get(cacheKey));

      // 用相同 id 的不同对象 evict
      SpelTestUser user2 = new SpelTestUser(99L, "Bob");
      service.updateBySpelUser(user2);
      assertNull(cacheManager.get(cacheKey));
    }
  }

  /**
   * 测试 SpEL key 不同参数值 evict 不同缓存.
   */
  @Test
  void testEvictWithSpelKeyDifferentArgs() {
    try (AnnotationConfigApplicationContext ctx =
        new AnnotationConfigApplicationContext(TestConfig.class)) {
      TestService service = ctx.getBean(TestService.class);
      CaffeineCacheManager cacheManager = ctx.getBean(CaffeineCacheManager.class);

      service.findBySpelKey(1L);
      service.findBySpelKey(2L);
      String key1 = "spelCache(1)";
      String key2 = "spelCache(2)";
      assertEquals("user_1", cacheManager.get(key1));
      assertEquals("user_2", cacheManager.get(key2));

      // 只清除 key1
      service.updateBySpelKey(1L);
      assertNull(cacheManager.get(key1));
      // key2 不受影响
      assertEquals("user_2", cacheManager.get(key2));
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
    public CacheKeyGenerator cacheKeyGenerator() {
      return new CacheKeyGenerator(
          new SpelExpressionParser(), new DefaultParameterNameDiscoverer());
    }

    @Bean
    public CacheAspect cacheAspect(CaffeineCacheManager cacheManager,
        CacheKeyGenerator cacheKeyGenerator) {
      return new CacheAspect(cacheManager, cacheKeyGenerator);
    }

    @Bean
    public CacheEvictAspect cacheEvictAspect(CaffeineCacheManager cacheManager,
        CacheKeyGenerator cacheKeyGenerator) {
      return new CacheEvictAspect(cacheManager, cacheKeyGenerator);
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
     * 根据 ID 查询（带缓存）.
     *
     * @param id 用户 ID
     * @return 用户名
     */
    @BingCache(expireTime = 60)
    public String findById(Long id) {
      callCount++;
      return "user_" + id;
    }

    /**
     * 更新用户（执行后清除对应缓存）.
     *
     * @param id 用户 ID
     */
    @BingCacheEvict(keyPrefix =
        "com.bing.cache.aspect.CacheEvictAspectTest$TestService.findById")
    public void updateUser(Long id) {
      callCount++;
    }

    /**
     * 更新用户（执行前清除缓存）.
     *
     * @param id 用户 ID
     */
    @BingCacheEvict(keyPrefix =
        "com.bing.cache.aspect.CacheEvictAspectTest$TestService.findById",
        beforeInvocation = true)
    public void deleteUserBefore(Long id) {
      callCount++;
    }

    /**
     * 更新用户（执行后清除，方法抛异常）.
     *
     * @param id 用户 ID
     */
    @BingCacheEvict(keyPrefix =
        "com.bing.cache.aspect.CacheEvictAspectTest$TestService.findById")
    public void updateUserWithError(Long id) {
      callCount++;
      throw new RuntimeException("simulated error");
    }

    /**
     * 删除用户（执行前清除，方法抛异常）.
     *
     * @param id 用户 ID
     */
    @BingCacheEvict(keyPrefix =
        "com.bing.cache.aspect.CacheEvictAspectTest$TestService.findById",
        beforeInvocation = true)
    public void deleteUserBeforeWithError(Long id) {
      callCount++;
      throw new RuntimeException("simulated error");
    }

    /**
     * 清空所有缓存.
     */
    @BingCacheEvict(allEntries = true)
    public void clearAll() {
      callCount++;
    }

    /**
     * 更新用户（使用 argIndexes）.
     *
     * @param id   用户 ID
     * @param name 用户名
     */
    @BingCacheEvict(keyPrefix =
        "com.bing.cache.aspect.CacheEvictAspectTest$TestService.findById",
        argIndexes = {0})
    public void updateUserWithArgIndexes(Long id, String name) {
      callCount++;
    }

    /**
     * 根据 ID 查询（使用 cacheName）.
     *
     * @param id 用户 ID
     * @return 用户名
     */
    @BingCache(cacheName = "user", expireTime = 60)
    public String findByCacheName(Long id) {
      callCount++;
      return "user_" + id;
    }

    /**
     * 更新用户（使用 cacheName 匹配缓存）.
     *
     * @param id 用户 ID
     */
    @BingCacheEvict(cacheName = "user", argIndexes = {0})
    public void updateUserByCacheName(Long id) {
      callCount++;
    }

    /**
     * 根据 dictType 查询字典（不同 cacheName）.
     *
     * @param dictType 字典类型
     * @return 字典值
     */
    @BingCache(cacheName = "dict", expireTime = 60)
    public String findDict(String dictType) {
      callCount++;
      return "dict_" + dictType;
    }

    /**
     * 按 cacheName 清除所有 user 缓存.
     */
    @BingCacheEvict(cacheName = "user", allEntries = true)
    public void clearAllUsers() {
      callCount++;
    }

    /**
     * 无 cacheName 的 allEntries 清空所有缓存.
     */
    @BingCacheEvict(allEntries = true)
    public void clearAllNoCacheName() {
      callCount++;
    }

    /**
     * 使用 SpEL key 缓存.
     *
     * @param id 用户 ID
     * @return 用户名
     */
    @BingCache(cacheName = "spelCache", argSpel = "#id")
    public String findBySpelKey(Long id) {
      callCount++;
      return "user_" + id;
    }

    /**
     * 使用 SpEL key 清除缓存.
     *
     * @param id 用户 ID
     */
    @BingCacheEvict(cacheName = "spelCache", argSpel = "#id")
    public void updateBySpelKey(Long id) {
      callCount++;
    }

    /**
     * 使用 SpEL key 选取对象属性缓存.
     *
     * @param user 用户对象
     * @return 用户名
     */
    @BingCache(cacheName = "spelUser", argSpel = "#user.id")
    public String findBySpelUser(SpelTestUser user) {
      callCount++;
      return "user_" + user.getId();
    }

    /**
     * 使用 SpEL key 选取对象属性清除缓存.
     *
     * @param user 用户对象
     */
    @BingCacheEvict(cacheName = "spelUser", argSpel = "#user.id")
    public void updateBySpelUser(SpelTestUser user) {
      callCount++;
    }

    public int getCallCount() {
      return callCount;
    }
  }

  /**
   * SpEL key 测试用的用户对象.
   */
  static class SpelTestUser {

    private final Long id;

    private final String name;

    SpelTestUser(Long id, String name) {
      this.id = id;
      this.name = name;
    }

    public Long getId() {
      return id;
    }

    public String getName() {
      return name;
    }
  }
}
