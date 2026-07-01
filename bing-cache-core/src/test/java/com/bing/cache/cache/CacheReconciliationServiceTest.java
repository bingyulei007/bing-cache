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
   * 测试非全局版本变化路径下，已废弃 cacheName 的本地基线被每周期 retainAll 清理.
   *
   * <p>场景：cacheName "user" 在首次对账后从 active 集合消失（但全局版本未变），
   * 表示该 cacheName 已不活跃。本地 lastKnownVersions 应被收敛到当前 active 集合，
   * 防止两次全局 clear() 之间已废弃 cacheName 的基线条目无限堆积。</p>
   */
  @Test
  void testStaleCacheNameRemovedByRetainAll() {
    // 首次对账：user 存在
    when(versionStore.getAllVersion()).thenReturn(0L);
    when(versionStore.getActiveCacheNames()).thenReturn(Optional.of(Set.of("user")));
    when(versionStore.getVersion("user")).thenReturn(1L);
    service.reconcile();
    assertTrue(lastKnownVersions().containsKey("user"));

    // 第二次对账：user 已不在 active 集合，全局版本未变
    when(versionStore.getActiveCacheNames()).thenReturn(Optional.of(Set.of()));
    service.reconcile();

    // user 应被 retainAll 清理
    assertFalse(lastKnownVersions().containsKey("user"));
  }

  /**
   * 测试非全局版本变化路径下，已废弃 group 的本地基线被每周期 retainAll 清理.
   */
  @Test
  void testStaleGroupRemovedByRetainAll() {
    // 首次对账：user group 存在
    when(versionStore.getAllVersion()).thenReturn(0L);
    when(versionStore.getActiveCacheNames()).thenReturn(Optional.of(Set.of()));
    when(versionStore.getActiveGroups()).thenReturn(Optional.of(Set.of("user")));
    when(versionStore.getGroupVersion("user")).thenReturn(1L);
    service.reconcile();
    assertTrue(lastKnownGroupVersions().containsKey("user"));

    // 第二次对账：user group 已不在 active 集合
    when(versionStore.getActiveGroups()).thenReturn(Optional.of(Set.of()));
    service.reconcile();

    assertFalse(lastKnownGroupVersions().containsKey("user"));
  }

  /**
   * 测试 SCAN 降级时 retainAll 不执行，保留已有基线状态.
   *
   * <p>SCAN 返回 Optional.empty() 表示本轮扫描不可用（集群模式瞬时状态等），
   * 此时不应执行 retainAll，避免基于空判断误清本地状态。</p>
   */
  @Test
  void testRetainAllSkippedWhenScanUnavailable() {
    // 首次对账：user 存在
    when(versionStore.getAllVersion()).thenReturn(0L);
    when(versionStore.getActiveCacheNames()).thenReturn(Optional.of(Set.of("user")));
    when(versionStore.getVersion("user")).thenReturn(1L);
    service.reconcile();
    assertTrue(lastKnownVersions().containsKey("user"));

    // 第二次对账：SCAN 不可用
    when(versionStore.getActiveCacheNames()).thenReturn(Optional.empty());
    service.reconcile();

    // 应保留状态，不清理
    assertTrue(lastKnownVersions().containsKey("user"));
  }

  /**
   * 测试首次对账时 group SCAN 降级，initialized 不应被置 true.
   *
   * <p>场景：首次对账 cacheName SCAN 成功但 group SCAN 返回 Optional.empty()
   * （Redis 抖动、集群模式瞬时状态）。按设计意图（字段 Javadoc 与 L175 注释），
   * initialized 只有在 cacheName 与 group 扫描都成功后才置 true。
   * 若 group 降级时仍置 true，下一次对账发现已存在的 group 版本 key 会走
   * "首次发现 + initialized=true" 分支误清 L1。</p>
   *
   * <p>本测试断言：首次对账 group 降级后，第二次对账 group 恢复且 Redis 中已有
   * group 版本号时，<b>不应</b>调用 clearByGroup。</p>
   */
  @Test
  void testGroupScanDegradedOnFirstCycleDoesNotFlipInitialized() {
    // 首次对账：cacheName 扫描成功（空集），group 扫描降级
    when(versionStore.getAllVersion()).thenReturn(0L);
    when(versionStore.getActiveCacheNames()).thenReturn(Optional.of(Set.of()));
    when(versionStore.getActiveGroups()).thenReturn(Optional.empty());
    service.reconcile();

    // 第二次对账：group 扫描恢复，Redis 中已有 user group 版本
    when(versionStore.getActiveGroups()).thenReturn(Optional.of(Set.of("user")));
    when(versionStore.getGroupVersion("user")).thenReturn(1L);
    service.reconcile();

    // initialized 应保持 false → "首次发现" 不应触发 clearByGroup
    verify(l1CacheManager, never()).clearByGroup(anyString());
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
