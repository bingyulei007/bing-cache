package com.bing.cache.integration;

import com.bing.cache.cache.CacheReconciliationService;
import com.bing.cache.cache.CacheVersionStore;
import com.bing.cache.cache.CaffeineCacheManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 版本对账机制集成测试.
 *
 * <p>使用 Testcontainers 启动真实 Redis，验证版本对账的端到端行为。</p>
 */
class ReconciliationIntegrationTest {

  private static final String VERSION_KEY_PREFIX = "bing-cache:version:";

  private static org.testcontainers.containers.GenericContainer<?> redisContainer;

  private StringRedisTemplate stringRedisTemplate;

  private CaffeineCacheManager l1CacheManager;

  private CacheVersionStore versionStore;

  private CacheReconciliationService reconciliationService;

  @BeforeAll
  static void startRedis() {
    redisContainer = new org.testcontainers.containers.GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);
    redisContainer.start();
  }

  @AfterAll
  static void stopRedis() {
    if (redisContainer != null) {
      redisContainer.stop();
    }
  }

  @BeforeEach
  void setUp() {
    String host = redisContainer.getHost();
    int port = redisContainer.getMappedPort(6379);

    RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
    RedisConnectionFactory connectionFactory = new LettuceConnectionFactory(config);
    ((LettuceConnectionFactory) connectionFactory).afterPropertiesSet();

    stringRedisTemplate = new StringRedisTemplate(connectionFactory);
    l1CacheManager = new CaffeineCacheManager(1000L, 300L);
    versionStore = new CacheVersionStore(stringRedisTemplate, VERSION_KEY_PREFIX);
    reconciliationService = new CacheReconciliationService(
        versionStore, l1CacheManager, 30L);

    // 清理 Redis 中的版本 key
    cleanRedisKeys();
  }

  @AfterEach
  void tearDown() {
    if (reconciliationService.isRunning()) {
      reconciliationService.stop();
    }
    cleanRedisKeys();
  }

  private void cleanRedisKeys() {
    var keys = stringRedisTemplate.keys(VERSION_KEY_PREFIX + "*");
    if (keys != null && !keys.isEmpty()) {
      stringRedisTemplate.delete(keys);
    }
  }

  /**
   * 全局版本变化时清空 L1.
   */
  @Test
  void testGlobalVersionChangeClearsAllL1() {
    // 向 L1 写入数据
    l1CacheManager.put("user([1])", "Alice", 60);
    l1CacheManager.put("dict([config])", "value", 60);

    // 首次对账
    reconciliationService.reconcile();

    // 递增全局版本号（模拟其他实例的 clear 操作）
    versionStore.incrementAllVersion();

    // 再次对账，应清空所有 L1
    reconciliationService.reconcile();

    assertNull(l1CacheManager.get("user([1])"));
    assertNull(l1CacheManager.get("dict([config])"));
  }

  /**
   * 前缀版本变化时按前缀清空.
   */
  @Test
  void testPrefixVersionChangeClearsByPrefix() {
    l1CacheManager.put("user([1])", "Alice", 60);
    l1CacheManager.put("dict([config])", "value", 60);

    // 首次对账
    reconciliationService.reconcile();

    // 递增 user 版本号
    versionStore.incrementVersion("user");

    // 再次对账，应只清除 user 前缀
    reconciliationService.reconcile();

    assertNull(l1CacheManager.get("user([1])"));
    assertEquals("value", l1CacheManager.get("dict([config])"));
  }

  /**
   * 版本无变化时不清空.
   */
  @Test
  void testNoVersionChangeDoesNotClear() {
    l1CacheManager.put("user([1])", "Alice", 60);

    // 首次对账
    reconciliationService.reconcile();

    // 不递增任何版本号，再次对账
    reconciliationService.reconcile();

    assertEquals("Alice", l1CacheManager.get("user([1])"));
  }

  /**
   * 多次版本变化的处理.
   */
  @Test
  void testMultipleVersionChanges() {
    l1CacheManager.put("user([1])", "Alice", 60);

    // 首次对账
    reconciliationService.reconcile();

    // 第一次版本变化
    versionStore.incrementVersion("user");
    reconciliationService.reconcile();
    assertNull(l1CacheManager.get("user([1])"));

    // 写入新数据
    l1CacheManager.put("user([2])", "Bob", 60);

    // 第二次版本变化
    versionStore.incrementVersion("user");
    reconciliationService.reconcile();
    assertNull(l1CacheManager.get("user([2])"));
  }

  /**
   * 生命周期管理（start/stop）.
   */
  @Test
  void testLifecycleManagement() {
    assert !reconciliationService.isRunning();
    reconciliationService.start();
    assert reconciliationService.isRunning();
    reconciliationService.stop();
    assert !reconciliationService.isRunning();
  }
}
