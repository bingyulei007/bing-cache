package com.bing.cache.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CacheReconciliationService 单元测试.
 */
class CacheReconciliationServiceTest {

  private CacheVersionStore versionStore;

  private CaffeineCacheManager l1CacheManager;

  private CacheReconciliationService service;

  @BeforeEach
  void setUp() {
    versionStore = mock(CacheVersionStore.class);
    l1CacheManager = mock(CaffeineCacheManager.class);
    service = new CacheReconciliationService(versionStore, l1CacheManager, 30L);
  }

  /**
   * 测试首次对账：记录当前版本号，不清除缓存.
   */
  @Test
  void testFirstReconciliationRecordsVersions() {
    when(versionStore.getAllVersion()).thenReturn(0L);
    when(versionStore.getActiveCacheNames()).thenReturn(Set.of("user"));
    when(versionStore.getVersion("user")).thenReturn(3L);

    service.reconcile();

    // 首次对账只记录版本号，不清除缓存
    verify(l1CacheManager, never()).clear();
    verify(l1CacheManager, never()).clearByPrefix(anyString());
  }

  /**
   * 测试全局版本号变化时清空所有 L1 缓存.
   */
  @Test
  void testGlobalVersionChangeClearsAllL1() {
    // 首次对账
    when(versionStore.getAllVersion()).thenReturn(1L);
    when(versionStore.getActiveCacheNames()).thenReturn(Set.of());
    service.reconcile();

    // 全局版本号变化
    when(versionStore.getAllVersion()).thenReturn(2L);
    service.reconcile();

    verify(l1CacheManager).clear();
  }

  /**
   * 测试 cacheName 版本号变化时按前缀清除 L1 缓存.
   */
  @Test
  void testCacheNameVersionChangeClearsByPrefix() {
    // 首次对账
    when(versionStore.getAllVersion()).thenReturn(0L);
    when(versionStore.getActiveCacheNames()).thenReturn(Set.of("user"));
    when(versionStore.getVersion("user")).thenReturn(1L);
    service.reconcile();

    // user 版本号变化
    when(versionStore.getVersion("user")).thenReturn(2L);
    service.reconcile();

    verify(l1CacheManager).clearByPrefix("user");
  }

  /**
   * 测试版本号未变化时不清除缓存.
   */
  @Test
  void testNoVersionChangeDoesNotClear() {
    // 首次对账
    when(versionStore.getAllVersion()).thenReturn(0L);
    when(versionStore.getActiveCacheNames()).thenReturn(Set.of("user"));
    when(versionStore.getVersion("user")).thenReturn(1L);
    service.reconcile();

    // 版本号未变化
    when(versionStore.getAllVersion()).thenReturn(0L);
    when(versionStore.getVersion("user")).thenReturn(1L);
    service.reconcile();

    // 只有首次记录，没有清除
    verify(l1CacheManager, never()).clear();
    verify(l1CacheManager, never()).clearByPrefix(anyString());
  }

  /**
   * 测试多个 cacheName 版本号变化.
   */
  @Test
  void testMultipleCacheNameVersionChanges() {
    // 首次对账
    when(versionStore.getAllVersion()).thenReturn(0L);
    when(versionStore.getActiveCacheNames()).thenReturn(Set.of("user", "dict"));
    when(versionStore.getVersion("user")).thenReturn(1L);
    when(versionStore.getVersion("dict")).thenReturn(1L);
    service.reconcile();

    // 两个 cacheName 都变化
    when(versionStore.getVersion("user")).thenReturn(2L);
    when(versionStore.getVersion("dict")).thenReturn(3L);
    service.reconcile();

    verify(l1CacheManager).clearByPrefix("user");
    verify(l1CacheManager).clearByPrefix("dict");
  }

  /**
   * 测试生命周期：start/stop.
   */
  @Test
  void testLifecycle() {
    assertFalse(service.isRunning());
    service.start();
    assertTrue(service.isRunning());
    service.stop();
    assertFalse(service.isRunning());
  }

  /**
   * 测试对账异常不影响后续运行.
   */
  @Test
  void testReconciliationExceptionDoesNotCrash() {
    when(versionStore.getAllVersion()).thenThrow(new RuntimeException("Redis error"));
    // 不应抛异常
    service.reconcile();
  }
}
