package com.bing.cache.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CompositeCacheManager 单元测试.
 */
class CompositeCacheManagerTest {

  private CaffeineCacheManager l1CacheManager;

  private RedisCacheManager l2CacheManager;

  private CacheInvalidationPublisher publisher;

  private CompositeCacheManager compositeCacheManager;

  @BeforeEach
  void setUp() {
    l1CacheManager = mock(CaffeineCacheManager.class);
    l2CacheManager = mock(RedisCacheManager.class);
    publisher = mock(CacheInvalidationPublisher.class);
    compositeCacheManager = new CompositeCacheManager(l1CacheManager, l2CacheManager, publisher);
  }

  @Test
  void testGetL1Hit() {
    when(l1CacheManager.get("user:1")).thenReturn("l1-value");
    Object result = compositeCacheManager.get("user:1");
    assertEquals("l1-value", result);
    verifyNoInteractions(l2CacheManager);
  }

  @Test
  void testGetL1MissL2Hit() {
    when(l1CacheManager.get("user:1")).thenReturn(null);
    when(l2CacheManager.get("user:1")).thenReturn("l2-value");
    when(l2CacheManager.getRemainingTtl("user:1")).thenReturn(180L);
    Object result = compositeCacheManager.get("user:1");
    assertEquals("l2-value", result);
    // L2 hit should backfill L1 with remaining TTL
    verify(l1CacheManager).put("user:1", "l2-value", 180L);
  }

  @Test
  void testGetBothMiss() {
    when(l1CacheManager.get("user:1")).thenReturn(null);
    when(l2CacheManager.get("user:1")).thenReturn(null);
    Object result = compositeCacheManager.get("user:1");
    assertNull(result);
  }

  @Test
  void testPutWritesToBothLevels() {
    compositeCacheManager.put("user:1", "value", 60);
    verify(l1CacheManager).put("user:1", "value", 60);
    verify(l2CacheManager).put("user:1", "value", 60);
  }

  /**
   * BingCacheNullValue 占位符只存 L1，不存 L2（Jackson 无法反序列化包私有类）.
   */
  @Test
  void testPutNullValueOnlyWritesL1() {
    Object nullValue = new BingCacheNullValue();
    compositeCacheManager.put("user:missing", nullValue, 60);
    verify(l1CacheManager).put("user:missing", nullValue, 60);
    verify(l2CacheManager, never()).put("user:missing", nullValue, 60);
  }

  /**
   * 模拟 BingCacheNullValue 占位符.
   */
  static final class BingCacheNullValue {
    static final BingCacheNullValue INSTANCE = new BingCacheNullValue();

    private BingCacheNullValue() {
    }
  }

  @Test
  void testEvictClearsBothLevelsAndPublishes() {
    compositeCacheManager.evict("user:1");
    verify(l1CacheManager).evict("user:1");
    verify(l2CacheManager).evict("user:1");
    verify(publisher).publishEvict("user:1");
  }

  @Test
  void testClearClearsBothLevelsAndPublishes() {
    compositeCacheManager.clear();
    verify(l1CacheManager).clear();
    verify(l2CacheManager).clear();
    verify(publisher).publishClear();
  }

  @Test
  void testGetL1CacheManager() {
    assertEquals(l1CacheManager, compositeCacheManager.getL1CacheManager());
  }

  @Test
  void testCreation() {
    assertNotNull(new CompositeCacheManager(l1CacheManager, l2CacheManager, publisher));
  }

  @Test
  void testClearByPrefixClearsBothLevelsAndPublishes() {
    compositeCacheManager.clearByPrefix("user");
    verify(l1CacheManager).clearByPrefix("user");
    verify(l2CacheManager).clearByPrefix("user");
    verify(publisher).publishClearByPrefix("user");
  }

  // ========== 回填竞态测试 ==========

  /**
   * 测试 L2 回填时 remainingTtl > 0 正常回填.
   */
  @Test
  void testBackfillWithPositiveRemainingTtl() {
    when(l1CacheManager.get("user:1")).thenReturn(null);
    when(l2CacheManager.get("user:1")).thenReturn("l2-value");
    when(l2CacheManager.getRemainingTtl("user:1")).thenReturn(298L);

    Object result = compositeCacheManager.get("user:1");
    assertEquals("l2-value", result);
    verify(l1CacheManager).put("user:1", "l2-value", 298L);
  }

  /**
   * 测试 L2 回填时 remainingTtl == -1（永不过期），L1 也永不过期.
   */
  @Test
  void testBackfillWithNoExpiry() {
    when(l1CacheManager.get("user:1")).thenReturn(null);
    when(l2CacheManager.get("user:1")).thenReturn("l2-value");
    when(l2CacheManager.getRemainingTtl("user:1")).thenReturn(-1L);

    Object result = compositeCacheManager.get("user:1");
    assertEquals("l2-value", result);
    verify(l1CacheManager).put("user:1", "l2-value", 0L);
  }

  /**
   * 测试 L2 回填时 remainingTtl == -2（key 不存在），跳过回填.
   */
  @Test
  void testBackfillSkippedWhenKeyNotFound() {
    when(l1CacheManager.get("user:1")).thenReturn(null);
    when(l2CacheManager.get("user:1")).thenReturn("l2-value");
    when(l2CacheManager.getRemainingTtl("user:1")).thenReturn(-2L);

    Object result = compositeCacheManager.get("user:1");
    // 返回 L2 的值，但不回填 L1
    assertEquals("l2-value", result);
    verify(l1CacheManager, never()).put(anyString(), any(), anyLong());
  }

  /**
   * 测试 L2 回填时 remainingTtl == 0（即将过期），跳过回填.
   */
  @Test
  void testBackfillSkippedWhenExpiring() {
    when(l1CacheManager.get("user:1")).thenReturn(null);
    when(l2CacheManager.get("user:1")).thenReturn("l2-value");
    when(l2CacheManager.getRemainingTtl("user:1")).thenReturn(0L);

    Object result = compositeCacheManager.get("user:1");
    assertEquals("l2-value", result);
    verify(l1CacheManager, never()).put(anyString(), any(), anyLong());
  }

  private void assertTrue(boolean condition) {
    if (!condition) {
      throw new AssertionError("Expected true but was false");
    }
  }
}
