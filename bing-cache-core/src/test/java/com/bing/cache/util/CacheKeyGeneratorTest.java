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

package com.bing.cache.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CacheKeyGenerator 单元测试.
 */
class CacheKeyGeneratorTest {

  private CacheKeyGenerator generator;

  @BeforeEach
  void setUp() {
    generator = new CacheKeyGenerator(
        new SpelExpressionParser(), new DefaultParameterNameDiscoverer());
  }

  /**
   * 测试使用默认前缀（类名.方法名 + 参数类型签名）生成 key.
   *
   * <p>默认前缀格式为 {@code className.methodName(paramTypes)}，包含参数类型签名
   * 以避免同名重载方法 key 碰撞。</p>
   */
  @Test
  void testGenerateWithDefaultPrefix() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findById", Long.class);
    Object[] args = {1L};

    String key = generator.generate(method, args, null, "", "", "", new int[]{}, "");

    assertEquals(
        "com.bing.cache.util.CacheKeyGeneratorTest$TestService.findById(java.lang.Long)([N:1])",
        key);
  }

  /**
   * 测试使用自定义 keyPrefix 生成 key.
   */
  @Test
  void testGenerateWithCustomPrefix() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findById", Long.class);
    Object[] args = {1L};

    String key = generator.generate(method, args, null, "", "", "user:detail", new int[]{}, "");

    assertEquals("user:detail([N:1])", key);
  }

  /**
   * 测试 cacheName 优先级高于 keyPrefix.
   */
  @Test
  void testCacheNameTakesPrecedenceOverKeyPrefix() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findById", Long.class);
    Object[] args = {1L};

    String key = generator.generate(method, args, null, "", "user", "user:detail", new int[]{}, "");

    assertEquals("user([N:1])", key);
  }

  /**
   * 测试 cacheName 优先级高于默认前缀.
   */
  @Test
  void testCacheNameTakesPrecedenceOverDefault() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findById", Long.class);
    Object[] args = {1L};

    String key = generator.generate(method, args, null, "", "user", "", new int[]{}, "");

    assertEquals("user([N:1])", key);
  }

  /**
   * 测试 cacheName 和 keyPrefix 都为空时使用默认前缀.
   */
  @Test
  void testFallbackToDefaultWhenBothEmpty() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findById", Long.class);
    Object[] args = {1L};

    String key = generator.generate(method, args, null, "", "", "", new int[]{}, "");

    assertEquals(
        "com.bing.cache.util.CacheKeyGeneratorTest$TestService.findById(java.lang.Long)([N:1])",
        key);
  }

  /**
   * 测试使用 argIndexes 过滤参数.
   */
  @Test
  void testGenerateWithArgIndexes() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("multiArg", String.class, Integer.class,
        Long.class);
    Object[] args = {"a", 2, 3L};

    String key = generator.generate(method, args, null, "", "", "", new int[]{0, 2}, "");

    assertEquals(
        "com.bing.cache.util.CacheKeyGeneratorTest$TestService.multiArg(java.lang.String,java.lang.Integer,java.lang.Long)([S:a, N:3])",
        key);
  }

  /**
   * 测试无参数方法生成 key.
   */
  @Test
  void testGenerateWithNoArgs() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("noArg");
    Object[] args = {};

    String key = generator.generate(method, args, null, "", "", "", new int[]{}, "");

    assertEquals(
        "com.bing.cache.util.CacheKeyGeneratorTest$TestService.noArg()([])", key);
  }

  /**
   * 测试不同参数生成不同 key.
   */
  @Test
  void testDifferentArgsProduceDifferentKeys() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findById", Long.class);

    String key1 = generator.generate(method, new Object[]{1L}, null, "", "", "", new int[]{}, "");
    String key2 = generator.generate(method, new Object[]{2L}, null, "", "", "", new int[]{}, "");

    assertNotEquals(key1, key2);
  }

  /**
   * 测试相同参数生成相同 key.
   */
  @Test
  void testSameArgsProduceSameKeys() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findById", Long.class);

    String key1 = generator.generate(method, new Object[]{1L}, null, "", "prefix", "", new int[]{}, "");
    String key2 = generator.generate(method, new Object[]{1L}, null, "", "prefix", "", new int[]{}, "");

    assertEquals(key1, key2);
  }

  /**
   * 测试自定义对象参数使用 Jackson 序列化生成 key.
   */
  @Test
  void testCustomObjectArgSerializedWithJackson() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findByUser", TestUser.class);
    TestUser user = new TestUser(1L, "Alice");
    Object[] args = {user};

    String key = generator.generate(method, args, null, "", "", "", new int[]{}, "");

    assertTrue(key.contains("\"id\":1"));
    assertTrue(key.contains("\"name\":\"Alice\""));
  }

  /**
   * 测试相同内容的自定义对象生成相同 key.
   */
  @Test
  void testSameCustomObjectProducesSameKey() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findByUser", TestUser.class);
    TestUser user1 = new TestUser(1L, "Alice");
    TestUser user2 = new TestUser(1L, "Alice");

    String key1 = generator.generate(method, new Object[]{user1}, null, "", "", "", new int[]{}, "");
    String key2 = generator.generate(method, new Object[]{user2}, null, "", "", "", new int[]{}, "");

    assertEquals(key1, key2);
  }

  /**
   * 测试不同内容的自定义对象生成不同 key.
   */
  @Test
  void testDifferentCustomObjectProducesDifferentKey() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findByUser", TestUser.class);
    TestUser user1 = new TestUser(1L, "Alice");
    TestUser user2 = new TestUser(2L, "Bob");

    String key1 = generator.generate(method, new Object[]{user1}, null, "", "", "", new int[]{}, "");
    String key2 = generator.generate(method, new Object[]{user2}, null, "", "", "", new int[]{}, "");

    assertNotEquals(key1, key2);
  }

  /**
   * 测试 null 参数生成 key.
   */
  @Test
  void testNullArgGeneratesKey() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findById", Long.class);
    Object[] args = {null};

    String key = generator.generate(method, args, null, "", "", "", new int[]{}, "");

    assertTrue(key.contains("null"));
  }

  /**
   * 测试超长 key 被截断到最大长度.
   */
  @Test
  void testLongKeyIsTruncated() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findById", Long.class);
    // 构造超长参数
    String longArg = "x".repeat(500);
    Object[] args = {longArg};

    String key = generator.generate(method, args, null, "", "", "", new int[]{}, "");

    assertEquals(CacheKeyGenerator.MAX_KEY_LENGTH, key.length());
    assertTrue(key.contains("...#"));
  }

  /**
   * 测试超长 key 截断后不同参数仍生成不同 key.
   */
  @Test
  void testTruncatedKeysAreStillUnique() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findById", Long.class);
    String longArg1 = "a".repeat(500);
    String longArg2 = "b".repeat(500);

    String key1 = generator.generate(method, new Object[]{longArg1}, null, "", "", "", new int[]{}, "");
    String key2 = generator.generate(method, new Object[]{longArg2}, null, "", "", "", new int[]{}, "");

    assertNotEquals(key1, key2);
  }

  /**
   * 测试基本类型数组参数不抛 ClassCastException.
   */
  @Test
  void testPrimitiveArrayArg() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findByInts", int[].class);
    int[] ints = {1, 2, 3};
    Object[] args = {ints};

    String key = generator.generate(method, args, null, "", "", "", new int[]{}, "");

    assertTrue(key.contains("[A:[N:1, N:2, N:3]]"));
  }

  /**
   * 测试 argIndexes 越界时抛出 IllegalArgumentException.
   */
  @Test
  void testArgIndexesOutOfRangeThrowsException() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("multiArg", String.class, Integer.class, Long.class);
    Object[] args = {"a", 2, 3L};

    // argIndexes 中包含越界索引 5，应抛出异常
    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> generator.generate(method, args, null, "", "", "", new int[]{0, 5}, ""));
    assertTrue(ex.getMessage().contains("argIndexes[1]=5"));
    assertTrue(ex.getMessage().contains("args length=3"));
  }

  /**
   * 测试 Jackson 序列化失败时抛出 IllegalStateException 而非静默降级为 hashCode.
   *
   * <p>循环引用对象会触发 JsonProcessingException。
   * 重启一致性要求 key 跨 JVM 启动保持一致，identity hashCode 无法保证这一点，
   * 因此应直接抛异常让调用方感知问题，而非生成不稳定的 key。</p>
   */
  @Test
  void testJacksonSerializationFailureThrowsException() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findByUser", TestUser.class);
    TestUser user = new TestUser(1L, "Alice");
    // 构造循环引用，触发 JsonProcessingException
    user.setPartner(user);
    Object[] args = {user};

    IllegalStateException ex = assertThrows(
        IllegalStateException.class,
        () -> generator.generate(method, args, null, "", "", "", new int[]{}, ""));
    assertTrue(ex.getMessage().contains("Failed to serialize argument of type"));
    assertTrue(ex.getCause() instanceof com.fasterxml.jackson.core.JsonProcessingException);
  }

  // ===== SpEL key 表达式测试 =====

  /**
   * 测试 SpEL 表达式按参数名选取简单类型.
   */
  @Test
  void testSpelKeyByParamName() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findById", Long.class);
    Object[] args = {1L};

    String key = generator.generate(method, args, null, "", "user", "", new int[]{}, "#id");

    assertEquals("user(N:1)", key);
  }

  /**
   * 测试 SpEL 表达式选取参数对象的属性.
   */
  @Test
  void testSpelKeyByProperty() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findByUser", TestUser.class);
    TestUser user = new TestUser(42L, "Bob");
    Object[] args = {user};

    String key = generator.generate(method, args, null, "", "user", "", new int[]{}, "#user.id");

    assertEquals("user(N:42)", key);
  }

  /**
   * 测试 SpEL 表达式拼接多个参数.
   */
  @Test
  void testSpelKeyConcatMultipleParams() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("multiArg", String.class, Integer.class,
        Long.class);
    Object[] args = {"a", 2, 3L};

    String key = generator.generate(method, args, null, "", "cache", "", new int[]{},
        "#a + ':' + #b + ':' + #c");

    assertEquals("cache(S:a:2:3)", key);
  }

  /**
   * 测试 SpEL 表达式使用 #p0 / #a0 索引变量.
   */
  @Test
  void testSpelKeyByIndex() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findById", Long.class);
    Object[] args = {99L};

    String keyP0 = generator.generate(method, args, null, "", "user", "", new int[]{}, "#p0");
    String keyA0 = generator.generate(method, args, null, "", "user", "", new int[]{}, "#a0");

    assertEquals("user(N:99)", keyP0);
    assertEquals("user(N:99)", keyA0);
  }

  /**
   * 测试 SpEL 表达式访问 #root.methodName.
   */
  @Test
  void testSpelKeyRootMethodName() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findById", Long.class);
    Object[] args = {1L};

    String key = generator.generate(method, args, null, "", "cache", "", new int[]{},
        "#root.methodName + ':' + #id");

    assertEquals("cache(S:findById:1)", key);
  }

  /**
   * 测试 SpEL 表达式求值结果为 null 时序列化为 "null".
   */
  @Test
  void testSpelKeyNullResult() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findByUser", TestUser.class);
    TestUser user = new TestUser(null, "Bob");
    Object[] args = {user};

    String key = generator.generate(method, args, null, "", "user", "", new int[]{}, "#user.id");

    assertEquals("user(null)", key);
  }

  /**
   * 测试 SpEL 表达式求值结果为自定义对象时使用 Jackson 序列化.
   */
  @Test
  void testSpelKeyCustomObjectResult() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findByUser", TestUser.class);
    TestUser user = new TestUser(1L, "Alice");
    Object[] args = {user};

    String key = generator.generate(method, args, null, "", "user", "", new int[]{}, "#user");

    assertTrue(key.contains("\"id\":1"));
    assertTrue(key.contains("\"name\":\"Alice\""));
  }

  /**
   * 测试 SpEL 表达式为空时回退到 argIndexes 逻辑.
   */
  @Test
  void testSpelKeyEmptyFallsBackToArgIndexes() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("multiArg", String.class, Integer.class,
        Long.class);
    Object[] args = {"a", 2, 3L};

    String keyWithSpel = generator.generate(method, args, null, "", "", "", new int[]{}, "");
    String keyWithArgIndexes = generator.generate(method, args, null, "", "", "",
        new int[]{0, 2}, "");

    assertEquals(
        "com.bing.cache.util.CacheKeyGeneratorTest$TestService.multiArg(java.lang.String,java.lang.Integer,java.lang.Long)([S:a, N:2, N:3])",
        keyWithSpel);
    assertEquals(
        "com.bing.cache.util.CacheKeyGeneratorTest$TestService.multiArg(java.lang.String,java.lang.Integer,java.lang.Long)([S:a, N:3])",
        keyWithArgIndexes);
  }

  /**
   * 测试 SpEL 表达式非空时忽略 argIndexes.
   */
  @Test
  void testSpelKeyOverridesArgIndexes() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("multiArg", String.class, Integer.class,
        Long.class);
    Object[] args = {"a", 2, 3L};

    String key = generator.generate(method, args, null, "", "cache", "",
        new int[]{0, 2}, "#c");

    assertEquals("cache(N:3)", key);
  }

  /**
   * 测试 SpEL 表达式语法错误时抛出 IllegalStateException.
   */
  @Test
  void testSpelKeySyntaxErrorThrowsException() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findById", Long.class);
    Object[] args = {1L};

    IllegalStateException ex = assertThrows(
        IllegalStateException.class,
        () -> generator.generate(method, args, null, "", "user", "", new int[]{},
            "#id +"));
    assertTrue(ex.getMessage().contains("Failed to evaluate SpEL key expression"));
  }

  /**
   * 测试相同 SpEL 表达式和参数生成相同 key.
   */
  @Test
  void testSpelKeySameArgsProduceSameKey() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findByUser", TestUser.class);
    TestUser user1 = new TestUser(1L, "Alice");
    TestUser user2 = new TestUser(1L, "Alice");

    String key1 = generator.generate(method, new Object[]{user1}, null, "", "user", "",
        new int[]{}, "#user.id");
    String key2 = generator.generate(method, new Object[]{user2}, null, "", "user", "",
        new int[]{}, "#user.id");

    assertEquals(key1, key2);
  }

  /**
   * 测试不同参数属性值生成不同 key.
   */
  @Test
  void testSpelKeyDifferentPropertyProducesDifferentKey() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findByUser", TestUser.class);
    TestUser user1 = new TestUser(1L, "Alice");
    TestUser user2 = new TestUser(2L, "Bob");

    String key1 = generator.generate(method, new Object[]{user1}, null, "", "user", "",
        new int[]{}, "#user.id");
    String key2 = generator.generate(method, new Object[]{user2}, null, "", "user", "",
        new int[]{}, "#user.id");

    assertNotEquals(key1, key2);
  }

  /**
   * 测试同名重载方法（参数类型不同）使用默认前缀时生成不同 key.
   *
   * <p>cacheName/keyPrefix 都未指定时，默认前缀包含参数类型签名，
   * 因此 findById(Long 1L) 和 findById(String "1") 即使参数序列化后
   * 字符串都是 "1"，也能通过类型签名区分，不会碰撞。</p>
   *
   * <p>该测试覆盖曾经存在的 bug：旧实现默认前缀只含 className.methodName，
   * 不含参数类型，导致重载方法 key 碰撞。</p>
   */
  @Test
  void testOverloadMethodsGenerateDifferentKeys() throws NoSuchMethodException {
    Method longMethod = TestService.class.getMethod("findById", Long.class);
    Method stringMethod = TestService.class.getMethod("findById", String.class);

    // 1L 和 "1" 序列化后都是 "1"，但参数类型不同
    String keyFromLong = generator.generate(longMethod, new Object[]{1L}, null,
        "", "", "", new int[]{}, "");
    String keyFromString = generator.generate(stringMethod, new Object[]{"1"}, null,
        "", "", "", new int[]{}, "");

    assertNotEquals(keyFromLong, keyFromString,
        "重载方法 + 不同类型参数序列化后字符串相同时，应通过参数类型签名区分");
    assertTrue(keyFromLong.contains("java.lang.Long"),
        "默认前缀应包含参数类型签名：java.lang.Long");
    assertTrue(keyFromString.contains("java.lang.String"),
        "默认前缀应包含参数类型签名：java.lang.String");
  }

  /**
   * 测试相同 cacheName 下整数数值归一化，但字符串值与数值区分.
   */
  @Test
  void testSameCacheNameNormalizesIntegralNumbersButDistinguishesString()
      throws NoSuchMethodException {
    Method intMethod = TestService.class.getMethod("findById", int.class);
    Method longMethod = TestService.class.getMethod("findById", Long.class);
    Method stringMethod = TestService.class.getMethod("findById", String.class);

    String keyFromInt = generator.generate(intMethod, new Object[]{1}, null,
        "", "user", "", new int[]{}, "");
    String keyFromLong = generator.generate(longMethod, new Object[]{1L}, null,
        "", "user", "", new int[]{}, "");
    String keyFromString = generator.generate(stringMethod, new Object[]{"1"}, null,
        "", "user", "", new int[]{}, "");

    assertEquals("user([N:1])", keyFromInt);
    assertEquals(keyFromInt, keyFromLong);
    assertEquals("user([S:1])", keyFromString);
    assertNotEquals(keyFromInt, keyFromString);
  }

  /**
   * 测试用的 Service 类.
   */
  static class TestService {

    public String findById(Long id) {
      return "result";
    }

    public String findById(int id) {
      return "result";
    }

    public String findById(String id) {
      return "result";
    }

    public String multiArg(String a, Integer b, Long c) {
      return "result";
    }

    public String findByInts(int[] ints) {
      return "result";
    }

    public String noArg() {
      return "result";
    }

    public String findByUser(TestUser user) {
      return "result";
    }
  }

  /**
   * 测试用的自定义对象（不重写 toString）.
   */
  static class TestUser {

    private final Long id;

    private final String name;

    private TestUser partner;

    TestUser(Long id, String name) {
      this.id = id;
      this.name = name;
    }

    public Long getId() {
      return id;
    }

    public String getName() {
      return name;
    }

    public TestUser getPartner() {
      return partner;
    }

    public void setPartner(TestUser partner) {
      this.partner = partner;
    }
  }
}
