package com.bing.cache.util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CacheKeyGenerator 单元测试.
 */
class CacheKeyGeneratorTest {

  /**
   * 测试使用默认前缀（类名.方法名）生成 key.
   */
  @Test
  void testGenerateWithDefaultPrefix() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findById", Long.class);
    Object[] args = {1L};

    String key = CacheKeyGenerator.generate(method, args, "", "", new int[]{});

    assertEquals(
        "com.bing.cache.util.CacheKeyGeneratorTest$TestService.findById([1])", key);
  }

  /**
   * 测试使用自定义 keyPrefix 生成 key.
   */
  @Test
  void testGenerateWithCustomPrefix() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findById", Long.class);
    Object[] args = {1L};

    String key = CacheKeyGenerator.generate(method, args, "", "user:detail", new int[]{});

    assertEquals("user:detail([1])", key);
  }

  /**
   * 测试 cacheName 优先级高于 keyPrefix.
   */
  @Test
  void testCacheNameTakesPrecedenceOverKeyPrefix() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findById", Long.class);
    Object[] args = {1L};

    String key = CacheKeyGenerator.generate(method, args, "user", "user:detail", new int[]{});

    assertEquals("user([1])", key);
  }

  /**
   * 测试 cacheName 优先级高于默认前缀.
   */
  @Test
  void testCacheNameTakesPrecedenceOverDefault() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findById", Long.class);
    Object[] args = {1L};

    String key = CacheKeyGenerator.generate(method, args, "user", "", new int[]{});

    assertEquals("user([1])", key);
  }

  /**
   * 测试 cacheName 和 keyPrefix 都为空时使用默认前缀.
   */
  @Test
  void testFallbackToDefaultWhenBothEmpty() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findById", Long.class);
    Object[] args = {1L};

    String key = CacheKeyGenerator.generate(method, args, "", "", new int[]{});

    assertEquals(
        "com.bing.cache.util.CacheKeyGeneratorTest$TestService.findById([1])", key);
  }

  /**
   * 测试使用 argIndexes 过滤参数.
   */
  @Test
  void testGenerateWithArgIndexes() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("multiArg", String.class, Integer.class,
        Long.class);
    Object[] args = {"a", 2, 3L};

    String key = CacheKeyGenerator.generate(method, args, "", "", new int[]{0, 2});

    assertEquals(
        "com.bing.cache.util.CacheKeyGeneratorTest$TestService.multiArg([a, 3])",
        key);
  }

  /**
   * 测试无参数方法生成 key.
   */
  @Test
  void testGenerateWithNoArgs() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("noArg");
    Object[] args = {};

    String key = CacheKeyGenerator.generate(method, args, "", "", new int[]{});

    assertEquals(
        "com.bing.cache.util.CacheKeyGeneratorTest$TestService.noArg([])", key);
  }

  /**
   * 测试不同参数生成不同 key.
   */
  @Test
  void testDifferentArgsProduceDifferentKeys() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findById", Long.class);

    String key1 = CacheKeyGenerator.generate(method, new Object[]{1L}, "", "", new int[]{});
    String key2 = CacheKeyGenerator.generate(method, new Object[]{2L}, "", "", new int[]{});

    assertNotEquals(key1, key2);
  }

  /**
   * 测试相同参数生成相同 key.
   */
  @Test
  void testSameArgsProduceSameKeys() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findById", Long.class);

    String key1 = CacheKeyGenerator.generate(method, new Object[]{1L}, "prefix", "", new int[]{});
    String key2 = CacheKeyGenerator.generate(method, new Object[]{1L}, "prefix", "", new int[]{});

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

    String key = CacheKeyGenerator.generate(method, args, "", "", new int[]{});

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

    String key1 = CacheKeyGenerator.generate(method, new Object[]{user1}, "", "", new int[]{});
    String key2 = CacheKeyGenerator.generate(method, new Object[]{user2}, "", "", new int[]{});

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

    String key1 = CacheKeyGenerator.generate(method, new Object[]{user1}, "", "", new int[]{});
    String key2 = CacheKeyGenerator.generate(method, new Object[]{user2}, "", "", new int[]{});

    assertNotEquals(key1, key2);
  }

  /**
   * 测试 null 参数生成 key.
   */
  @Test
  void testNullArgGeneratesKey() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("findById", Long.class);
    Object[] args = {null};

    String key = CacheKeyGenerator.generate(method, args, "", "", new int[]{});

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

    String key = CacheKeyGenerator.generate(method, args, "", "", new int[]{});

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

    String key1 = CacheKeyGenerator.generate(method, new Object[]{longArg1}, "", "", new int[]{});
    String key2 = CacheKeyGenerator.generate(method, new Object[]{longArg2}, "", "", new int[]{});

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

    String key = CacheKeyGenerator.generate(method, args, "", "", new int[]{});

    assertTrue(key.contains("[[1, 2, 3]]"));
  }

  /**
   * 测试 argIndexes 越界时抛出 IllegalArgumentException.
   */
  @Test
  void testArgIndexesOutOfRangeThrowsException() throws NoSuchMethodException {
    Method method = TestService.class.getMethod("multiArg", String.class, Integer.class, Long.class);
    Object[] args = {"a", 2, 3L};

    // argIndexes 中包含越界索引 5，应抛出异常
    IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> CacheKeyGenerator.generate(method, args, "", "", new int[]{0, 5}));
    assertTrue(ex.getMessage().contains("argIndexes[1]=5"));
    assertTrue(ex.getMessage().contains("args length=3"));
  }

  /**
   * 测试用的 Service 类.
   */
  static class TestService {

    public String findById(Long id) {
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
  }
}
