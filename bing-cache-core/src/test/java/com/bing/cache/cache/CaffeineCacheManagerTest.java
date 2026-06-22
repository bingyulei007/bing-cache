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

package com.bing.cache.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CaffeineCacheManager 单元测试.
 */
class CaffeineCacheManagerTest {

  private CaffeineCacheManager cacheManager;

  @BeforeEach
  void setUp() {
    cacheManager = new CaffeineCacheManager();
  }

  /**
   * 测试基本的存取操作.
   */
  @Test
  void testPutAndGet() {
    cacheManager.put("key1", "value1", 0);

    Object result = cacheManager.get("key1");

    assertEquals("value1", result);
  }

  /**
   * 测试获取不存在的 key 返回 null.
   */
  @Test
  void testGetNonExistentKey() {
    Object result = cacheManager.get("not_exist");

    assertNull(result);
  }

  /**
   * 测试带过期时间的存取.
   */
  @Test
  void testPutAndGetWithExpiry() {
    cacheManager.put("key2", "value2", 60);

    Object result = cacheManager.get("key2");

    assertEquals("value2", result);
  }

  /**
   * 测试不同过期时间的缓存互不影响.
   */
  @Test
  void testDifferentExpiryTimes() {
    cacheManager.put("keyA", "valueA", 0);
    cacheManager.put("keyB", "valueB", 60);

    assertEquals("valueA", cacheManager.get("keyA"));
    assertEquals("valueB", cacheManager.get("keyB"));
  }

  /**
   * 测试 evict 移除指定 key.
   */
  @Test
  void testEvict() {
    cacheManager.put("key3", "value3", 0);
    cacheManager.put("key4", "value4", 60);

    cacheManager.evict("key3");

    assertNull(cacheManager.get("key3"));
    assertEquals("value4", cacheManager.get("key4"));
  }

  /**
   * 测试 clear 清空所有缓存.
   */
  @Test
  void testClear() {
    cacheManager.put("key6", "value6", 0);
    cacheManager.put("key7", "value7", 60);

    cacheManager.clear();

    assertNull(cacheManager.get("key6"));
    assertNull(cacheManager.get("key7"));
  }

  /**
   * 测试覆盖写入同一 key.
   */
  @Test
  void testOverwrite() {
    cacheManager.put("key8", "old", 0);
    cacheManager.put("key8", "new", 0);

    assertEquals("new", cacheManager.get("key8"));
  }

  /**
   * 测试存储不同类型的值.
   */
  @Test
  void testDifferentValueTypes() {
    cacheManager.put("str", "hello", 0);
    cacheManager.put("num", 42, 0);
    cacheManager.put("list", java.util.Arrays.asList(1, 2, 3), 0);

    assertEquals("hello", cacheManager.get("str"));
    assertEquals(42, cacheManager.get("num"));
    assertEquals(java.util.Arrays.asList(1, 2, 3), cacheManager.get("list"));
  }

  /**
   * 测试自定义 maxSize 构造器.
   */
  @Test
  void testCustomMaxSizeConstructor() {
    CaffeineCacheManager customManager = new CaffeineCacheManager(500L);
    customManager.put("key1", "value1", 0);
    assertEquals("value1", customManager.get("key1"));
  }

  /**
   * 测试按前缀清除缓存.
   */
  @Test
  void testClearByPrefix() {
    cacheManager.put("user([1])", "user1", 0);
    cacheManager.put("user([2])", "user2", 60);
    cacheManager.put("dict([config])", "dictValue", 0);

    cacheManager.clearByPrefix("user");

    assertNull(cacheManager.get("user([1])"));
    assertNull(cacheManager.get("user([2])"));
    assertEquals("dictValue", cacheManager.get("dict([config])"));
  }

  /**
   * 测试按前缀清除时不会误删前缀相似的缓存.
   *
   * <p>cacheName "user" 是 "userDetail" 的前缀，clearByPrefix("user")
   * 不应清除 "userDetail" 的缓存。</p>
   */
  @Test
  void testClearByPrefixDoesNotCollideWithSimilarPrefix() {
    cacheManager.put("user([1])", "user1", 0);
    cacheManager.put("userDetail([1])", "detail1", 0);

    cacheManager.clearByPrefix("user");

    assertNull(cacheManager.get("user([1])"));
    assertEquals("detail1", cacheManager.get("userDetail([1])"));
  }

  /**
   * 测试按前缀清除时前缀不匹配任何 key.
   */
  @Test
  void testClearByPrefixNoMatch() {
    cacheManager.put("user([1])", "user1", 0);
    cacheManager.put("dict([config])", "dictValue", 0);

    cacheManager.clearByPrefix("order");

    assertEquals("user1", cacheManager.get("user([1])"));
    assertEquals("dictValue", cacheManager.get("dict([config])"));
  }

  // ========== per-entry Expiry 相关测试 ==========

  /**
   * 测试带过期时间的条目实际过期.
   */
  @Test
  void testEntryExpiry() throws InterruptedException {
    CaffeineCacheManager manager = new CaffeineCacheManager(1000L);
    manager.put("expiring", "value", 1); // 1 秒过期

    assertEquals("value", manager.get("expiring"));

    // 等待过期
    Thread.sleep(1500);
    assertNull(manager.get("expiring"));
  }

  /**
   * 测试永不过期的条目不会消失.
   */
  @Test
  void testNoExpiryEntryPersists() throws InterruptedException {
    CaffeineCacheManager manager = new CaffeineCacheManager(1000L);
    manager.put("permanent", "value", 0); // 不过期

    Thread.sleep(500);
    assertEquals("value", manager.get("permanent"));
  }

  /**
   * 测试不同过期时间的条目独立过期.
   */
  @Test
  void testDifferentEntriesExpireIndependently() throws InterruptedException {
    CaffeineCacheManager manager = new CaffeineCacheManager(1000L);
    manager.put("short", "value1", 1); // 1 秒
    manager.put("long", "value2", 10); // 10 秒

    Thread.sleep(1500);
    assertNull(manager.get("short"));
    assertEquals("value2", manager.get("long"));
  }

  /**
   * 测试覆盖写入同一 key 时更新过期时间.
   */
  @Test
  void testOverwriteUpdatesExpiry() throws InterruptedException {
    CaffeineCacheManager manager = new CaffeineCacheManager(1000L);
    manager.put("key", "old", 1); // 1 秒
    manager.put("key", "new", 10); // 覆盖为 10 秒

    Thread.sleep(1500);
    assertEquals("new", manager.get("key")); // 旧的 1 秒已过，但新的 10 秒未过
  }

  // ========== l1MaxTtl 相关测试 ==========

  /**
   * 测试 l1MaxTtl 限制过期时间.
   */
  @Test
  void testL1MaxTtlLimitsExpiry() {
    CaffeineCacheManager manager = new CaffeineCacheManager(1000L, 5L); // maxTtl=5s
    // 请求 60 秒过期，但 L1 最多 5 秒
    manager.put("key", "value", 60);
    assertEquals("value", manager.get("key"));
    assertEquals(5L, manager.getL1MaxTtlSeconds());
  }

  /**
   * 测试 l1MaxTtl=0 时不限制过期时间.
   */
  @Test
  void testL1MaxTtlZeroNoLimit() {
    CaffeineCacheManager manager = new CaffeineCacheManager(1000L, 0L);
    manager.put("key", "value", 3600);
    assertEquals("value", manager.get("key"));
    assertEquals(0L, manager.getL1MaxTtlSeconds());
  }

  /**
   * 测试 l1MaxTtl 对永不过期条目生效.
   */
  @Test
  void testL1MaxTtlAppliesToNoExpiryEntry() {
    CaffeineCacheManager manager = new CaffeineCacheManager(1000L, 300L); // maxTtl=300s
    // 原本永不过期，但 maxTtl 限制为 300 秒
    manager.put("key", "value", 0);
    assertEquals("value", manager.get("key"));
  }

  /**
   * 测试 l1MaxTtl 小于请求的过期时间时，使用 maxTtl.
   */
  @Test
  void testL1MaxTtlShorterThanRequested() throws InterruptedException {
    CaffeineCacheManager manager = new CaffeineCacheManager(1000L, 1L); // maxTtl=1s
    manager.put("key", "value", 60); // 请求 60 秒，实际 1 秒

    assertEquals("value", manager.get("key"));
    Thread.sleep(1500);
    assertNull(manager.get("key")); // 1 秒后过期
  }

  // ========== keys() 方法测试 ==========

  /**
   * 测试 keys() 返回当前所有 key.
   */
  @Test
  void testKeys() {
    cacheManager.put("key1", "value1", 0);
    cacheManager.put("key2", "value2", 0);

    assertTrue(cacheManager.keys().contains("key1"));
    assertTrue(cacheManager.keys().contains("key2"));
  }
}
