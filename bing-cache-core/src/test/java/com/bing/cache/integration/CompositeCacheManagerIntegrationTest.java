package com.bing.cache.integration;

import com.bing.cache.cache.CacheInvalidationListener;
import com.bing.cache.cache.CacheInvalidationPublisher;
import com.bing.cache.cache.CaffeineCacheManager;
import com.bing.cache.cache.CompositeCacheManager;
import com.bing.cache.cache.RedisCacheInvalidationPublisher;
import com.bing.cache.cache.RedisCacheManager;
import com.bing.cache.config.BingCacheProperties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * CompositeCacheManager 集成测试.
 *
 * <p>使用嵌入式 Redis (GenericContainer) 验证 L1+L2 二级缓存
 * 的完整读写和 Pub/Sub 跨实例失效功能。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class CompositeCacheManagerIntegrationTest {

  private static final String KEY_PREFIX = "bing-cache:";

  private static final String CHANNEL_NAME = "bing-cache:invalidation";

  private static org.testcontainers.containers.GenericContainer<?> redisContainer;

  private CompositeCacheManager cacheManager;

  private CaffeineCacheManager l1Only;

  private StringRedisTemplate stringRedisTemplate;

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

    RedisTemplate<String, Object> objectRedisTemplate = new RedisTemplate<>();
    objectRedisTemplate.setConnectionFactory(connectionFactory);
    objectRedisTemplate.setKeySerializer(new StringRedisSerializer());
    objectRedisTemplate.setValueSerializer(new GenericJackson2JsonRedisSerializer());
    objectRedisTemplate.afterPropertiesSet();

    BingCacheProperties properties = new BingCacheProperties();
    CaffeineCacheManager l1 = new CaffeineCacheManager(properties.getCaffeine().getMaxSize());
    RedisCacheManager l2 = new RedisCacheManager(objectRedisTemplate, KEY_PREFIX);
    CacheInvalidationPublisher publisher =
        new RedisCacheInvalidationPublisher(stringRedisTemplate, CHANNEL_NAME);
    cacheManager = new CompositeCacheManager(l1, l2, publisher);

    l1Only = l1;

    // Clean up Redis keys from previous tests
    cleanRedisKeys(objectRedisTemplate);
  }

  private void cleanRedisKeys(RedisTemplate<String, Object> redisTemplate) {
    try (var cursor = redisTemplate.scan(
        org.springframework.data.redis.core.ScanOptions.scanOptions()
            .match(KEY_PREFIX + "*").count(100).build())) {
      java.util.List<String> keys = new java.util.ArrayList<>();
      while (cursor.hasNext()) {
        keys.add(cursor.next());
      }
      if (!keys.isEmpty()) {
        redisTemplate.delete(keys);
      }
    }
  }

  @Test
  void testL1AndL2WriteAndRead() {
    cacheManager.put("user:1", "Alice", 60);
    // L1 should have it
    assertEquals("Alice", l1Only.get("user:1"));
    // L2 (Redis) should have it
    assertEquals("Alice", cacheManager.get("user:1"));
  }

  @Test
  void testL2HitBackfillsL1() {
    // Write directly to L2 (bypassing composite)
    cacheManager.put("user:2", "Bob", 60);
    // Evict from L1 only
    l1Only.evict("user:2");
    assertNull(l1Only.get("user:2"));
    // Get via composite — should hit L2 and backfill L1
    Object result = cacheManager.get("user:2");
    assertEquals("Bob", result);
    // Now L1 should have it
    assertEquals("Bob", l1Only.get("user:2"));
  }

  @Test
  void testEvictClearsBothLevels() {
    cacheManager.put("user:3", "Charlie", 60);
    cacheManager.evict("user:3");
    assertNull(l1Only.get("user:3"));
    // L2 should also be cleared (get returns null from both)
    assertNull(cacheManager.get("user:3"));
  }

  @Test
  void testClearClearsBothLevels() {
    cacheManager.put("user:4", "Dave", 60);
    cacheManager.put("user:5", "Eve", 60);
    cacheManager.clear();
    assertNull(l1Only.get("user:4"));
    assertNull(l1Only.get("user:5"));
  }

  @Test
  void testPubSubCrossInstanceInvalidation() throws Exception {
    // Set up a second "instance" with its own L1 and shared L2
    BingCacheProperties properties = new BingCacheProperties();
    CaffeineCacheManager l1Instance2 = new CaffeineCacheManager(properties.getCaffeine().getMaxSize());

    // Set up Pub/Sub listener for instance 2
    RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
        redisContainer.getHost(), redisContainer.getMappedPort(6379));
    LettuceConnectionFactory connectionFactory2 = new LettuceConnectionFactory(config);
    connectionFactory2.afterPropertiesSet();

    CacheInvalidationListener listener2 = new CacheInvalidationListener(l1Instance2,
        "instance-2");
    MessageListenerAdapter adapter = new MessageListenerAdapter(listener2, "handleMessage");
    adapter.afterPropertiesSet();
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory2);
    container.addMessageListener(adapter, new PatternTopic(CHANNEL_NAME));
    container.afterPropertiesSet();

    // Both instances write data
    cacheManager.put("shared:key", "value1", 60);
    l1Instance2.put("shared:key", "value1", 0); // instance 2 also caches locally

    // Instance 1 evicts the key (should publish to Redis)
    cacheManager.evict("shared:key");

    // Wait for Pub/Sub delivery
    Thread.sleep(500);

    // Instance 2's L1 should be invalidated
    assertNull(l1Instance2.get("shared:key"));

    container.destroy();
    connectionFactory2.destroy();
  }

  @Test
  void testTtlInRedis() {
    cacheManager.put("ttl:key", "value", 2);
    // Key should exist in Redis with TTL
    Long ttl = stringRedisTemplate.getExpire(KEY_PREFIX + "ttl:key", java.util.concurrent.TimeUnit.SECONDS);
    assertEquals(true, ttl != null && ttl > 0 && ttl <= 2);
  }

  private void assertEquals(Object expected, Object actual) {
    if (expected == null && actual == null) {
      return;
    }
    if (expected != null && expected.equals(actual)) {
      return;
    }
    throw new AssertionError("Expected <" + expected + "> but was <" + actual + ">");
  }

  private void assertNull(Object actual) {
    if (actual != null) {
      throw new AssertionError("Expected null but was <" + actual + ">");
    }
  }
}
