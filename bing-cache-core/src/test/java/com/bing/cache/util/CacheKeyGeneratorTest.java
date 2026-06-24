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
        "com.bing.cache.util.CacheKeyGeneratorTest$TestService.findById(java.lang.Long)(Sg[N:1])",
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

    assertEquals("user:detail(Sg[N:1])", key);
  }

  /**
   * 测试 cacheName 优先级高于 keyPrefix.
   */
  @Test
  void testCacheNameTakesPrecedenceOverKeyPrefix() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findById", Long.class);
    Object[] args = {1L};

    String key = generator.generate(method, args, null, "", "user", "user:detail", new int[]{}, "");

    assertEquals("user(Sg[N:1])", key);
  }

  /**
   * 测试 cacheName 优先级高于默认前缀.
   */
  @Test
  void testCacheNameTakesPrecedenceOverDefault() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findById", Long.class);
    Object[] args = {1L};

    String key = generator.generate(method, args, null, "", "user", "", new int[]{}, "");

    assertEquals("user(Sg[N:1])", key);
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
        "com.bing.cache.util.CacheKeyGeneratorTest$TestService.findById(java.lang.Long)(Sg[N:1])",
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
   *
   * <p>无参数方法 args 部分为空字符串，key 形如 {@code prefix()}。
   * 默认前缀本身已含 {@code ()}（方法签名），故最终 key 为 {@code prefix()()}。</p>
   */
  @Test
  void testGenerateWithNoArgs() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("noArg");
    Object[] args = {};

    String key = generator.generate(method, args, null, "", "", "", new int[]{}, "");

    assertEquals(
        "com.bing.cache.util.CacheKeyGeneratorTest$TestService.noArg()()", key);
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
   *
   * <p>数组作为单参数走 {@code serializeSingle}，输出 {@code Sg[[N:1, N:2, N:3]]}。
   * 内层 {@code [N:1, N:2, N:3]} 是数组的元素序列化，外层 {@code Sg[...]} 标识单值选取。</p>
   */
  @Test
  void testPrimitiveArrayArg() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findByInts", int[].class);
    int[] ints = {1, 2, 3};
    Object[] args = {ints};

    String key = generator.generate(method, args, null, "", "", "", new int[]{}, "");

    assertTrue(key.contains("(Sg[[N:1, N:2, N:3]])"));
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

    assertEquals("user(Sg[N:1])", key);
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

    assertEquals("user(Sg[N:42])", key);
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

    assertEquals("cache(Sg[S:a:2:3])", key);
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

    assertEquals("user(Sg[N:99])", keyP0);
    assertEquals("user(Sg[N:99])", keyA0);
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

    assertEquals("cache(Sg[S:findById:1])", key);
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

    assertEquals("user(Sg[null])", key);
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

    assertEquals("cache(Sg[N:3])", key);
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

    assertEquals("user(Sg[N:1])", keyFromInt);
    assertEquals(keyFromInt, keyFromLong);
    assertEquals("user(Sg[S:1])", keyFromString);
    assertNotEquals(keyFromInt, keyFromString);
  }

  /**
   * 测试 argSpel 多值场景与 argIndexes/默认方式产出一致.
   *
   * <p>argSpel 返回 List 时，serializeArg 对 List 逐元素加类型前缀，
   * 与 argIndexes/默认方式通过 serializeArgs 拼接的形式一致。
   * 这是 SpEL 路径向类型前缀方案靠拢的关键验证。</p>
   */
  @Test
  void testSpelListMatchesArgIndexesAndDefault() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("multiArg", String.class, Integer.class,
        Long.class);
    Object[] args = {"a", 2, 3L};

    String keyByArgIndexes = generator.generate(method, args, null, "", "cache", "",
        new int[]{0, 1, 2}, "");
    String keyByDefault = generator.generate(method, args, null, "", "cache", "",
        new int[]{}, "");
    String keyBySpel = generator.generate(method, args, null, "", "cache", "",
        new int[]{}, "{#root.args[0], #root.args[1], #root.args[2]}");

    assertEquals("cache([S:a, N:2, N:3])", keyByArgIndexes);
    assertEquals(keyByArgIndexes, keyByDefault);
    assertEquals(keyByArgIndexes, keyBySpel);
  }

  /**
   * 测试 argSpel 单值场景与 argIndexes/默认方式产出一致.
   *
   * <p>Sg 方案：单值选取(argSpel / argIndexes={0} / 单参数默认)都输出 {@code Sg[...]}。
   * 三种方式都生成 {@code user(Sg[N:123])}。</p>
   */
  @Test
  void testSpelSingleValueMatchesArgIndexesAndDefault() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findById", Long.class);
    Object[] args = {123L};

    String keyBySpel = generator.generate(method, args, null, "", "user", "",
        new int[]{}, "#id");
    String keyByArgIndexes = generator.generate(method, args, null, "", "user", "",
        new int[]{0}, "");
    String keyByDefault = generator.generate(method, args, null, "", "user", "",
        new int[]{}, "");

    assertEquals("user(Sg[N:123])", keyBySpel);
    assertEquals(keyBySpel, keyByArgIndexes);
    assertEquals(keyBySpel, keyByDefault);
  }

  /**
   * 测试 argSpel 多值({@code {...}})与 argIndexes/默认多参数产出一致.
   *
   * <p>Sg 方案：argSpel 以 {@code {}} 形式表示多值选取，与 argIndexes/默认多参数
   * 都输出 {@code [N:1, N:2]}（不带 Sg）。</p>
   */
  @Test
  void testSpelMultiValueMatchesArgIndexesAndDefault() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("multiArg", String.class, Integer.class,
        Long.class);
    Object[] args = {"a", 2, 3L};

    String keyBySpel = generator.generate(method, args, null, "", "cache", "",
        new int[]{}, "{#root.args[0], #root.args[1], #root.args[2]}");
    String keyByArgIndexes = generator.generate(method, args, null, "", "cache", "",
        new int[]{0, 1, 2}, "");
    String keyByDefault = generator.generate(method, args, null, "", "cache", "",
        new int[]{}, "");

    assertEquals("cache([S:a, N:2, N:3])", keyBySpel);
    assertEquals(keyBySpel, keyByArgIndexes);
    assertEquals(keyBySpel, keyByDefault);
  }

  /**
   * 测试单值 List 参数与多参数不碰撞.
   *
   * <p>Sg 方案核心目标：单值 List 参数输出 {@code Sg[N:1, N:2]}，
   * 多参数输出 {@code [N:1, N:2]}，通过 {@code Sg[} vs {@code [} 区分，不碰撞。</p>
   */
  @Test
  void testSingleListArgDoesNotCollideWithMultiArgs() throws NoSuchMethodException {
    // 单参数 List<Integer>
    Method listMethod = TestService.class.getMethod("findByInts", int[].class);
    int[] ints = {1, 2};
    String keyFromList = generator.generate(listMethod, new Object[]{ints}, null,
        "", "cache", "", new int[]{}, "");

    // 两参数 (Integer, Integer) - 用 multiArg 模拟
    Method multiMethod = TestService.class.getMethod("multiArg", String.class, Integer.class,
        Long.class);
    String keyFromMulti = generator.generate(multiMethod, new Object[]{"x", 1, 2L}, null,
        "", "cache", "", new int[]{1, 2}, "");

    // 两者形式不同，不碰撞
    assertNotEquals(keyFromList, keyFromMulti);
    assertTrue(keyFromList.contains("Sg["));
    assertTrue(keyFromMulti.contains("([") && !keyFromMulti.contains("Sg["));
  }

  /**
   * 测试多值 SpEL 表达式允许首尾及参数间存在空格.
   *
   * <p>只要满足 "{" 开头 "}" 结尾、每个参数以 "#" 开头、参数用 "," 分隔，
   * 多余的空格不影响多值判定。</p>
   */
  @Test
  void testSpelMultiValueIgnoresWhitespace() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("multiArg", String.class, Integer.class,
        Long.class);
    Object[] args = {"a", 2, 3L};

    String key = generator.generate(method, args, null, "", "cache", "",
        new int[]{}, "{ #root.args[0] , #root.args[1] , #root.args[2] }");

    assertEquals("cache([S:a, N:2, N:3])", key);
  }

  /**
   * 测试顶层逗号与嵌套逗号的区分：字符串字面量/数组构造器里的逗号不影响顶层切分.
   *
   * <p>{@code {#root.args[0], 'x'}} 顶层有两个表达式，因此走多值路径，
   * 输出 {@code [S:a, S:x]}。</p>
   */
  @Test
  void testSpelMixedLiteralTreatedAsMultiValue() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("multiArg", String.class, Integer.class,
        Long.class);
    Object[] args = {"a", 2, 3L};

    String key = generator.generate(method, args, null, "", "cache", "",
        new int[]{}, "{#root.args[0], 'x'}");

    assertEquals("cache([S:a, S:x])", key);
  }

  /**
   * 测试数组构造器中的逗号被忽略，顶层只按两个元素参与多值切分.
   *
   * <p>{@code {new int[]{#a, #b}, #c}} 顶层有两个表达式：数组构造器和 {@code #c}，
   * 因此走多值路径，输出 {@code [[N:1, N:2], N:3]}（数组本身作为一个参数）。</p>
   */
  @Test
  void testSpelNestedArrayLiteralAsMultiValue() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("threeInts", int.class, int.class, int.class);
    Object[] args = {1, 2, 3};

    String key = generator.generate(method, args, null, "", "cache", "",
        new int[]{}, "{new int[]{#a, #b}, #c}");

    assertEquals("cache([[N:1, N:2], N:3])", key);
  }

  /**
   * 测试花括号内只有一个参数时按单值处理.
   *
   * <p>{@code {#a0}} 虽然以 "{}" 包裹，但只有一个参数，
   * 不满足多值“至少两个参数”的要求，因此输出 {@code Sg[...]}。</p>
   */
  @Test
  void testSpelSingleParamInBracesTreatedAsSingleValue() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findByInts", int[].class);
    int[] ints = {1, 2};
    Object[] args = {ints};

    String key = generator.generate(method, args, null, "", "cache", "",
        new int[]{}, "{#a0}");

    assertEquals("cache(Sg[[N:1, N:2]])", key);
  }

  // ==================== 保留名校验测试 ====================

  @Test
  void testGroupVersionReserved() {
    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> generator.generate(getMethod("findById", Long.class), new Object[]{1L}, null,
            "__version__", "user", null, null, null));
    assertTrue(ex.getMessage().contains("__version__"));
    assertTrue(ex.getMessage().contains("reserved"));
  }

  @Test
  void testGroupAllReserved() {
    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> generator.generate(getMethod("findById", Long.class), new Object[]{1L}, null,
            "__all__", "user", null, null, null));
    assertTrue(ex.getMessage().contains("__all__"));
    assertTrue(ex.getMessage().contains("reserved"));
  }

  @Test
  void testGroupPrefixReserved() {
    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> generator.generate(getMethod("findById", Long.class), new Object[]{1L}, null,
            "__group__:user", "detail", null, null, null));
    assertTrue(ex.getMessage().contains("__group__:"));
    assertTrue(ex.getMessage().contains("reserved"));
  }

  @Test
  void testCacheNameAllReserved() {
    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> generator.generate(getMethod("findById", Long.class), new Object[]{1L}, null,
            null, "__all__", null, null, null));
    assertTrue(ex.getMessage().contains("__all__"));
    assertTrue(ex.getMessage().contains("reserved"));
  }

  @Test
  void testCacheNameGroupPrefixReserved() {
    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> generator.generate(getMethod("findById", Long.class), new Object[]{1L}, null,
            null, "__group__:user", null, null, null));
    assertTrue(ex.getMessage().contains("__group__:"));
    assertTrue(ex.getMessage().contains("reserved"));
  }

  @Test
  void testValidateReservedNameAllowsNormalValues() {
    CacheKeyGenerator.validateReservedName("group", "user");
    CacheKeyGenerator.validateReservedName("group", "orders");
    CacheKeyGenerator.validateReservedCacheName("userDetail");
    CacheKeyGenerator.validateReservedCacheName("config");
  }

  @Test
  void testValidateReservedNameAllowsEmptyOrNull() {
    CacheKeyGenerator.validateReservedName("group", null);
    CacheKeyGenerator.validateReservedName("group", "");
    CacheKeyGenerator.validateReservedCacheName(null);
    CacheKeyGenerator.validateReservedCacheName("");
  }

  private Method getMethod(String name, Class<?>... paramTypes) {
    try {
      return TestService.class.getMethod(name, paramTypes);
    } catch (NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
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

    public String threeInts(int a, int b, int c) {
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
