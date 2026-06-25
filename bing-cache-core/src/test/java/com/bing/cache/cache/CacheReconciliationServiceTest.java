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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
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
    when(versionStore.getActiveCacheNames()).thenReturn(Optional.of(Set.of("user")));
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
    when(versionStore.getActiveCacheNames()).thenReturn(Optional.of(Set.of()));
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
    when(versionStore.getActiveCacheNames()).thenReturn(Optional.of(Set.of("user")));
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
    when(versionStore.getActiveCacheNames()).thenReturn(Optional.of(Set.of("user")));
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
    when(versionStore.getActiveCacheNames()).thenReturn(Optional.of(Set.of("user", "dict")));
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
   * 测试全局版本刷新时扫描不可用：保留已有 cacheName 版本状态.
   */
  @Test
  void testRefreshAllKnownVersionsUnavailablePreservesState() {
    when(versionStore.getAllVersion()).thenReturn(0L);
    when(versionStore.getActiveCacheNames()).thenReturn(Optional.of(Set.of("user")));
    when(versionStore.getVersion("user")).thenReturn(1L);
    service.reconcile();

    when(versionStore.getAllVersion()).thenReturn(1L);
    when(versionStore.getActiveCacheNames()).thenReturn(Optional.empty());
    service.reconcile();

    assertTrue(lastKnownVersions().containsKey("user"));
  }

  /**
   * 测试全局版本刷新时真实空集：清理已废弃 cacheName 版本状态.
   */
  @Test
  void testRefreshAllKnownVersionsEmptySetClearsState() {
    when(versionStore.getAllVersion()).thenReturn(0L);
    when(versionStore.getActiveCacheNames()).thenReturn(Optional.of(Set.of("user")));
    when(versionStore.getVersion("user")).thenReturn(1L);
    service.reconcile();

    when(versionStore.getAllVersion()).thenReturn(1L);
    when(versionStore.getActiveCacheNames()).thenReturn(Optional.of(Set.of()));
    service.reconcile();

    assertTrue(lastKnownVersions().isEmpty());
  }

  /**
   * 测试 group 版本号变化时按 group 清除 L1 缓存.
   */
  @Test
  void testGroupVersionChangeClearsByGroup() {
    // 首次对账：记录 group 版本号
    when(versionStore.getAllVersion()).thenReturn(0L);
    when(versionStore.getActiveCacheNames()).thenReturn(Optional.of(Set.of()));
    when(versionStore.getActiveGroups()).thenReturn(Optional.of(Set.of("user")));
    when(versionStore.getGroupVersion("user")).thenReturn(1L);
    service.reconcile();

    // group 版本号变化
    when(versionStore.getGroupVersion("user")).thenReturn(2L);
    service.reconcile();

    verify(l1CacheManager).clearByGroup("user");
  }

  /**
   * 测试 group 版本号未变化时不清除缓存.
   */
  @Test
  void testGroupVersionUnchangedDoesNotClear() {
    // 首次对账
    when(versionStore.getAllVersion()).thenReturn(0L);
    when(versionStore.getActiveCacheNames()).thenReturn(Optional.of(Set.of()));
    when(versionStore.getActiveGroups()).thenReturn(Optional.of(Set.of("user")));
    when(versionStore.getGroupVersion("user")).thenReturn(1L);
    service.reconcile();

    // 版本号未变化
    when(versionStore.getGroupVersion("user")).thenReturn(1L);
    service.reconcile();

    verify(l1CacheManager, never()).clearByGroup(anyString());
  }

  /**
   * 测试全局版本变化时刷新 group 版本状态（清理已废弃 group）.
   */
  @Test
  void testGlobalVersionChangeRefreshesGroupVersions() {
    // 首次对账：记录 group 版本
    when(versionStore.getAllVersion()).thenReturn(0L);
    when(versionStore.getActiveCacheNames()).thenReturn(Optional.of(Set.of()));
    when(versionStore.getActiveGroups()).thenReturn(Optional.of(Set.of("user", "order")));
    when(versionStore.getGroupVersion("user")).thenReturn(1L);
    when(versionStore.getGroupVersion("order")).thenReturn(1L);
    service.reconcile();
    assertTrue(lastKnownGroupVersions().containsKey("user"));
    assertTrue(lastKnownGroupVersions().containsKey("order"));

    // 全局版本变化，order group 已废弃
    when(versionStore.getAllVersion()).thenReturn(1L);
    when(versionStore.getActiveCacheNames()).thenReturn(Optional.of(Set.of()));
    when(versionStore.getActiveGroups()).thenReturn(Optional.of(Set.of("user")));
    when(versionStore.getGroupVersion("user")).thenReturn(1L);
    service.reconcile();

    // order 应被清理，user 保留
    assertFalse(lastKnownGroupVersions().containsKey("order"));
    assertTrue(lastKnownGroupVersions().containsKey("user"));
  }

  /**
   * 测试全局版本号从未创建状态（0）递增到 1 时清空所有 L1 缓存.
   */
  @Test
  void testGlobalVersionFromZeroToOneClearsAllL1() {
    when(versionStore.getAllVersion()).thenReturn(0L);
    when(versionStore.getActiveCacheNames()).thenReturn(Optional.of(Set.of()));
    when(versionStore.getActiveGroups()).thenReturn(Optional.of(Set.of()));
    service.reconcile();

    when(versionStore.getAllVersion()).thenReturn(1L);
    service.reconcile();

    verify(l1CacheManager).clear();
  }

  /**
   * 测试首次对账后新出现的 cacheName 版本号会按前缀清除 L1 缓存.
   */
  @Test
  void testNewCacheNameAfterInitialReconciliationClearsByPrefix() {
    when(versionStore.getAllVersion()).thenReturn(0L);
    when(versionStore.getActiveCacheNames()).thenReturn(Optional.of(Set.of()));
    when(versionStore.getActiveGroups()).thenReturn(Optional.of(Set.of()));
    service.reconcile();

    when(versionStore.getActiveCacheNames()).thenReturn(Optional.of(Set.of("user")));
    when(versionStore.getVersion("user")).thenReturn(1L);
    service.reconcile();

    verify(l1CacheManager).clearByPrefix("user");
  }

  /**
   * 测试首次对账后新出现的 group 版本号会按 group 清除 L1 缓存.
   */
  @Test
  void testNewGroupAfterInitialReconciliationClearsByGroup() {
    when(versionStore.getAllVersion()).thenReturn(0L);
    when(versionStore.getActiveCacheNames()).thenReturn(Optional.of(Set.of()));
    when(versionStore.getActiveGroups()).thenReturn(Optional.of(Set.of()));
    service.reconcile();

    when(versionStore.getActiveGroups()).thenReturn(Optional.of(Set.of("user")));
    when(versionStore.getGroupVersion("user")).thenReturn(1L);
    service.reconcile();

    verify(l1CacheManager).clearByGroup("user");
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

  @SuppressWarnings("unchecked")
  private Map<String, Long> lastKnownVersions() {
    try {
      Field field = CacheReconciliationService.class.getDeclaredField("lastKnownVersions");
      field.setAccessible(true);
      return (Map<String, Long>) field.get(service);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Long> lastKnownGroupVersions() {
    try {
      Field field = CacheReconciliationService.class.getDeclaredField("lastKnownGroupVersions");
      field.setAccessible(true);
      return (Map<String, Long>) field.get(service);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}
