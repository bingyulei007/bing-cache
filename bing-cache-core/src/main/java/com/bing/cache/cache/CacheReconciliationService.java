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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.SmartLifecycle;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 缓存版本对账服务.
 *
 * <p>定时检查 Redis 中的版本号变化，当发现版本号变化时，
 * 清除对应前缀的 L1 缓存，作为 Redis Pub/Sub 消息丢失的补偿机制。</p>
 *
 * <p>实现 {@link SmartLifecycle}，随 Spring 容器启停，
 * 确保服务在容器关闭时正确释放资源。</p>
 */
public class CacheReconciliationService implements SmartLifecycle {

  private static final Logger LOG = LoggerFactory.getLogger(CacheReconciliationService.class);

  private final CacheVersionStore versionStore;

  private final CacheManager l1CacheManager;

  private final long intervalSeconds;

  private final Map<String, Long> lastKnownVersions = new ConcurrentHashMap<>();

  private volatile long lastKnownAllVersion = 0L;

  private ScheduledExecutorService scheduler;

  private volatile boolean running = false;

  /**
   * 构造方法.
   *
   * @param versionStore     版本号存储
   * @param l1CacheManager   L1 本地缓存管理器
   * @param intervalSeconds  对账间隔秒数
   */
  public CacheReconciliationService(CacheVersionStore versionStore,
      CacheManager l1CacheManager,
      long intervalSeconds) {
    this.versionStore = versionStore;
    this.l1CacheManager = l1CacheManager;
    this.intervalSeconds = intervalSeconds;
  }

  @Override
  public void start() {
    if (running) {
      return;
    }
    running = true;
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "bing-cache-reconciliation");
      t.setDaemon(true);
      return t;
    });
    // initialDelay=0，服务启动后立即执行一次对账
    scheduler.scheduleAtFixedRate(this::reconcile, 0, intervalSeconds, TimeUnit.SECONDS);
    LOG.info("Bing Cache: Reconciliation service started, interval={}s", intervalSeconds);
  }

  @Override
  public void stop() {
    if (!running) {
      return;
    }
    running = false;
    if (scheduler != null) {
      scheduler.shutdown();
      try {
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
          scheduler.shutdownNow();
        }
      } catch (InterruptedException e) {
        scheduler.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
    LOG.info("Bing Cache: Reconciliation service stopped");
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /**
   * 执行一次对账.
   *
   * <p>检查全局版本号和各 cacheName 的版本号，发现变化时清除对应 L1 缓存。</p>
   */
  public void reconcile() {
    try {
      // 先检查全局版本号
      long currentAllVersion = versionStore.getAllVersion();
      if (currentAllVersion != lastKnownAllVersion) {
        if (lastKnownAllVersion != 0L) {
          LOG.info("Bing Cache: Global version changed ({} -> {}), clearing all L1 cache",
              lastKnownAllVersion, currentAllVersion);
          l1CacheManager.clear();
        }
        lastKnownAllVersion = currentAllVersion;
        // 全局版本变化时，各 cacheName 的版本号也需要刷新
        refreshAllKnownVersions();
        return;
      }

      // 检查各 cacheName 的版本号
      Set<String> activeNames = versionStore.getActiveCacheNames();
      for (String cacheName : activeNames) {
        checkCacheNameVersion(cacheName);
      }
    } catch (Exception e) {
      LOG.error("Bing Cache: Reconciliation failed", e);
    }
  }

  /**
   * 检查指定 cacheName 的版本号变化.
   *
   * @param cacheName 缓存名称
   */
  private void checkCacheNameVersion(String cacheName) {
    long currentVersion = versionStore.getVersion(cacheName);
    Long lastVersion = lastKnownVersions.get(cacheName);

    if (lastVersion == null) {
      // 首次发现此 cacheName，记录当前版本号
      lastKnownVersions.put(cacheName, currentVersion);
      return;
    }

    if (currentVersion != lastVersion) {
      LOG.info("Bing Cache: Version changed for '{}' ({} -> {}), clearing L1 by prefix",
          cacheName, lastVersion, currentVersion);
      l1CacheManager.clearByPrefix(cacheName);
      lastKnownVersions.put(cacheName, currentVersion);
    }
  }

  /**
   * 全局版本变化后，刷新所有已知 cacheName 的版本号.
   *
   * <p>同时清理已不存在于 Redis 中的过期 cacheName，防止 lastKnownVersions 无限增长。</p>
   */
  private void refreshAllKnownVersions() {
    Set<String> activeNames = versionStore.getActiveCacheNames();
    // 清理已废弃的 cacheName，防止内存泄漏
    lastKnownVersions.keySet().retainAll(activeNames);
    for (String cacheName : activeNames) {
      lastKnownVersions.put(cacheName, versionStore.getVersion(cacheName));
    }
  }
}
