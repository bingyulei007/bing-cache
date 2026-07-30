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

package com.bing.cache.aspect;

import com.bing.cache.annotation.BingCache;
import com.bing.cache.cache.CacheManager;
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
   * 测试 cacheNullValue=true 且 expireTime=0 时，null 占位符使用兜底 TTL 而非永久驻留.
   *
   * <p>验证修复：纯 L1 模式下 {@code cacheNullValue=true} + {@code expireTime=0} 不再导致
   * null 占位符永久驻留 L1，而是套用 {@code NULL_VALUE_FALLBACK_TTL_SECONDS}（300s）。</p>
   */
  @Test
  void testNullValueFallbackTtlWhenExpireZero() {
    try (AnnotationConfigApplicationContext ctx =
        new AnnotationConfigApplicationContext(RecordingTestConfig.class)) {
      TestService service = ctx.getBean(TestService.class);
      RecordingCacheManager recordingManager = ctx.getBean(RecordingCacheManager.class);

      assertNull(service.findNeverExpireNull("missing"));

      // 应当写入一次，且 expireSeconds 为兜底值而非 0
      assertEquals(1, recordingManager.putCount);
      assertEquals(CacheAspect.NULL_VALUE_FALLBACK_TTL_SECONDS,
          recordingManager.lastPutExpireSeconds,
          "expireTime=0 的 null 值应使用兜底 TTL，而非永久驻留");
    }
  }

  /**
   * 测试 cacheNullValue=true 且 expireTime>0 时，尊重用户配置的 expireTime.
   *
   * <p>验证兜底逻辑只在 {@code expireTime<=0} 时触发，正数 expireTime 不受影响。</p>
   */
  @Test
  void testNullValueRespectsPositiveExpireTime() {
    try (AnnotationConfigApplicationContext ctx =
        new AnnotationConfigApplicationContext(RecordingTestConfig.class)) {
      TestService service = ctx.getBean(TestService.class);
      RecordingCacheManager recordingManager = ctx.getBean(RecordingCacheManager.class);

      assertNull(service.findByNameNull("missing"));

      assertEquals(1, recordingManager.putCount);
      assertEquals(30L, recordingManager.lastPutExpireSeconds,
          "expireTime>0 时应尊重用户配置，不触发兜底");
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
   * 测试 SpEL key 表达式生成缓存 key 并命中.
   */
  @Test
  void testSpelKeyCacheHit() {
    try (AnnotationConfigApplicationContext ctx =
        new AnnotationConfigApplicationContext(TestConfig.class)) {
      TestService service = ctx.getBean(TestService.class);
      TestService target = ctx.getBean("testServiceTarget", TestService.class);

      String result1 = service.findByIdSpel(1L);
      String result2 = service.findByIdSpel(1L);

      assertEquals("user_1", result1);
      assertEquals(1, target.getCallCount());
    }
  }

  /**
   * 测试 SpEL key 不同参数走不同缓存.
   */
  @Test
  void testSpelKeyDifferentArgsDifferentCache() {
    try (AnnotationConfigApplicationContext ctx =
        new AnnotationConfigApplicationContext(TestConfig.class)) {
      TestService service = ctx.getBean(TestService.class);
      TestService target = ctx.getBean("testServiceTarget", TestService.class);

      service.findByIdSpel(1L);
      service.findByIdSpel(2L);

      assertEquals(2, target.getCallCount());
    }
  }

  /**
   * 测试 SpEL key 选取参数对象属性.
   */
  @Test
  void testSpelKeyByProperty() {
    try (AnnotationConfigApplicationContext ctx =
        new AnnotationConfigApplicationContext(TestConfig.class)) {
      TestService service = ctx.getBean(TestService.class);
      TestService target = ctx.getBean("testServiceTarget", TestService.class);

      SpelTestUser user1 = new SpelTestUser(10L, "Alice");
      SpelTestUser user2 = new SpelTestUser(10L, "Bob");

      service.findByUserSpel(user1);
      service.findByUserSpel(user2);

      // key = "spelUser(10)"，不同 name 但相同 id 应命中同一缓存
      assertEquals(1, target.getCallCount());
    }
  }

  /**
   * 测试 SpEL key 拼接多个参数.
   */
  @Test
  void testSpelKeyConcatMultipleParams() {
    try (AnnotationConfigApplicationContext ctx =
        new AnnotationConfigApplicationContext(TestConfig.class)) {
      TestService service = ctx.getBean(TestService.class);
      TestService target = ctx.getBean("testServiceTarget", TestService.class);

      service.findMultiSpel("a", 1L);
      service.findMultiSpel("a", 1L);
      service.findMultiSpel("b", 1L);

      // "a:1" 命中缓存，"b:1" 未命中
      assertEquals(2, target.getCallCount());
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
    public TestService testServiceTarget() {
      return new TestService();
    }
  }

  /**
   * 用于 null 值兜底 TTL 测试的配置类.
   *
   * <p>使用 {@link RecordingCacheManager}（实现 {@link CacheManager}）作为 CacheManager，
   * 以便测试获取并验证 put 时的 expireSeconds 参数。</p>
   */
  @Configuration
  @EnableAspectJAutoProxy
  static class RecordingTestConfig {

    @Bean
    public RecordingCacheManager cacheManager() {
      return new RecordingCacheManager();
    }

    @Bean
    public CacheKeyGenerator cacheKeyGenerator() {
      return new CacheKeyGenerator(
          new SpelExpressionParser(), new DefaultParameterNameDiscoverer());
    }

    @Bean
    public CacheAspect cacheAspect(RecordingCacheManager cacheManager,
        CacheKeyGenerator cacheKeyGenerator) {
      return new CacheAspect(cacheManager, cacheKeyGenerator);
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

    /**
     * 根据名称查询，cacheNullValue=true 且 expireTime=0（永不过期）.
     *
     * <p>用于验证 null 值兜底 TTL：expireTime=0 时 null 占位符应套用
     * {@code NULL_VALUE_FALLBACK_TTL_SECONDS} 而非永久驻留。</p>
     *
     * @param name 名称
     * @return 结果
     */
    @BingCache(keyPrefix = "user:name:null:noexpire", cacheNullValue = true)
    public String findNeverExpireNull(String name) {
      callCount++;
      return null;
    }

    /**
     * 使用 SpEL key 按 ID 查询.
     *
     * @param id 用户 ID
     * @return 用户名
     */
    @BingCache(cacheName = "spelUser", argSpel = "#id")
    public String findByIdSpel(Long id) {
      callCount++;
      return "user_" + id;
    }

    /**
     * 使用 SpEL key 选取参数对象属性.
     *
     * @param user 用户对象
     * @return 用户名
     */
    @BingCache(cacheName = "spelUser", argSpel = "#user.id")
    public String findByUserSpel(SpelTestUser user) {
      callCount++;
      return "user_" + user.getId();
    }

    /**
     * 使用 SpEL key 拼接多个参数.
     *
     * @param prefix 前缀
     * @param id     用户 ID
     * @return 拼接结果
     */
    @BingCache(cacheName = "spelMulti", argSpel = "#prefix + ':' + #id")
    public String findMultiSpel(String prefix, Long id) {
      callCount++;
      return prefix + "_" + id;
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

  /**
   * 记录 put 调用参数的 CacheManager 桩，用于验证 null 值兜底 TTL.
   *
   * <p>get 永远返回 null（模拟缓存未命中），put 时记录传入的 expireSeconds。
   * 其他操作为空实现。</p>
   */
  static class RecordingCacheManager implements CacheManager {

    volatile int putCount = 0;
    volatile long lastPutExpireSeconds = -1L;
    volatile Object lastPutValue = null;

    @Override
    public Object get(String key) {
      return null;
    }

    @Override
    public void put(String key, Object value, long expireSeconds) {
      putCount++;
      lastPutExpireSeconds = expireSeconds;
      lastPutValue = value;
    }

    @Override
    public void evict(String key) {
      // no-op
    }

    @Override
    public void clear() {
      // no-op
    }

    @Override
    public void clearByPrefix(String prefix) {
      // no-op
    }

    @Override
    public void clearByGroup(String group) {
      // no-op
    }
  }
}
