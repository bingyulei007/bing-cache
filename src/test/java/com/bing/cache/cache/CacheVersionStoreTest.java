package com.bing.cache.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Iterator;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * CacheVersionStore 单元测试.
 */
class CacheVersionStoreTest {

  private StringRedisTemplate stringRedisTemplate;

  private ValueOperations<String, String> valueOperations;

  private CacheVersionStore versionStore;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    stringRedisTemplate = mock(StringRedisTemplate.class);
    valueOperations = mock(ValueOperations.class);
    when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    versionStore = new CacheVersionStore(stringRedisTemplate, "bing-cache:version:");
  }

  @Test
  void testIncrementVersion() {
    when(valueOperations.increment("bing-cache:version:user")).thenReturn(1L);
    long version = versionStore.incrementVersion("user");
    assertEquals(1L, version);
  }

  @Test
  void testIncrementAllVersion() {
    when(valueOperations.increment("bing-cache:version:__all__")).thenReturn(1L);
    long version = versionStore.incrementAllVersion();
    assertEquals(1L, version);
  }

  @Test
  void testGetVersionExisting() {
    when(valueOperations.get("bing-cache:version:user")).thenReturn("5");
    long version = versionStore.getVersion("user");
    assertEquals(5L, version);
  }

  @Test
  void testGetVersionNonExistent() {
    when(valueOperations.get("bing-cache:version:nonexistent")).thenReturn(null);
    long version = versionStore.getVersion("nonexistent");
    assertEquals(0L, version);
  }

  @Test
  void testGetVersionInvalidValue() {
    when(valueOperations.get("bing-cache:version:bad")).thenReturn("not-a-number");
    long version = versionStore.getVersion("bad");
    assertEquals(0L, version);
  }

  @Test
  void testGetAllVersion() {
    when(valueOperations.get("bing-cache:version:__all__")).thenReturn("3");
    long version = versionStore.getAllVersion();
    assertEquals(3L, version);
  }

  @Test
  @SuppressWarnings("unchecked")
  void testGetActiveCacheNames() {
    // 模拟 SCAN 返回的 key 字节数据
    Iterator<byte[]> keyIterator = java.util.Arrays.asList(
        "bing-cache:version:user".getBytes(),
        "bing-cache:version:dict".getBytes(),
        "bing-cache:version:__all__".getBytes()
    ).iterator();
    Cursor<byte[]> cursor = mock(Cursor.class);
    when(cursor.hasNext()).thenAnswer(inv -> keyIterator.hasNext());
    when(cursor.next()).thenAnswer(inv -> keyIterator.next());

    RedisConnection connection = mock(RedisConnection.class);
    RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
    when(connection.keyCommands()).thenReturn(keyCommands);
    when(keyCommands.scan(any(ScanOptions.class))).thenReturn(cursor);

    when(stringRedisTemplate.execute(any(RedisCallback.class)))
        .thenAnswer(inv -> {
          RedisCallback<?> callback = inv.getArgument(0);
          return callback.doInRedis(connection);
        });

    Set<String> names = versionStore.getActiveCacheNames();
    assertEquals(3, names.size());
    assertTrue(names.contains("user"));
    assertTrue(names.contains("dict"));
    assertTrue(names.contains("__all__"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void testGetActiveCacheNamesEmpty() {
    Iterator<byte[]> keyIterator = java.util.Collections.<byte[]>emptyList().iterator();
    Cursor<byte[]> cursor = mock(Cursor.class);
    when(cursor.hasNext()).thenAnswer(inv -> keyIterator.hasNext());
    when(cursor.next()).thenAnswer(inv -> keyIterator.next());

    RedisConnection connection = mock(RedisConnection.class);
    RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
    when(connection.keyCommands()).thenReturn(keyCommands);
    when(keyCommands.scan(any(ScanOptions.class))).thenReturn(cursor);

    when(stringRedisTemplate.execute(any(RedisCallback.class)))
        .thenAnswer(inv -> {
          RedisCallback<?> callback = inv.getArgument(0);
          return callback.doInRedis(connection);
        });

    Set<String> names = versionStore.getActiveCacheNames();
    assertTrue(names.isEmpty());
  }
}
