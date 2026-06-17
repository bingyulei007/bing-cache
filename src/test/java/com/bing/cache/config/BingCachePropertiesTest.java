package com.bing.cache.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * BingCacheProperties 单元测试.
 */
class BingCachePropertiesTest {

  @Test
  void testDefaultValues() {
    BingCacheProperties properties = new BingCacheProperties();
    assertNotNull(properties.getCaffeine());
    assertNotNull(properties.getRedis());
    assertNotNull(properties.getReconciliation());
    assertEquals(1000L, properties.getCaffeine().getMaxSize());
    assertEquals(0L, properties.getCaffeine().getL1MaxTtl());
    assertTrue(properties.getRedis().isEnabled());
    assertEquals("bing-cache:", properties.getRedis().getKeyPrefix());
    assertEquals("bing-cache:invalidation", properties.getRedis().getChannelName());
    assertTrue(properties.getReconciliation().isEnabled());
    assertEquals(30L, properties.getReconciliation().getInterval());
  }

  @Test
  void testCustomCaffeineMaxSize() {
    BingCacheProperties properties = new BingCacheProperties();
    properties.getCaffeine().setMaxSize(2000L);
    assertEquals(2000L, properties.getCaffeine().getMaxSize());
  }

  @Test
  void testCustomL1MaxTtl() {
    BingCacheProperties properties = new BingCacheProperties();
    properties.getCaffeine().setL1MaxTtl(300L);
    assertEquals(300L, properties.getCaffeine().getL1MaxTtl());
  }

  @Test
  void testCustomRedisConfig() {
    BingCacheProperties properties = new BingCacheProperties();
    properties.getRedis().setEnabled(false);
    properties.getRedis().setKeyPrefix("myapp:");
    properties.getRedis().setChannelName("myapp:cache:invalidation");
    assertTrue(!properties.getRedis().isEnabled());
    assertEquals("myapp:", properties.getRedis().getKeyPrefix());
    assertEquals("myapp:cache:invalidation", properties.getRedis().getChannelName());
  }

  @Test
  void testCustomReconciliationConfig() {
    BingCacheProperties properties = new BingCacheProperties();
    properties.getReconciliation().setEnabled(false);
    properties.getReconciliation().setInterval(60L);
    assertTrue(!properties.getReconciliation().isEnabled());
    assertEquals(60L, properties.getReconciliation().getInterval());
  }

  private void assertTrue(boolean condition) {
    if (!condition) {
      throw new AssertionError("Expected true but was false");
    }
  }
}
