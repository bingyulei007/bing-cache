package com.bing.cache.cache;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CacheInvalidationListener 单元测试.
 */
class CacheInvalidationListenerTest {

  private CaffeineCacheManager l1CacheManager;

  private CacheInvalidationListener listener;

  private static final String INSTANCE_ID = "test-instance";

  @BeforeEach
  void setUp() {
    l1CacheManager = mock(CaffeineCacheManager.class);
    listener = new CacheInvalidationListener(l1CacheManager, INSTANCE_ID);
  }

  @Test
  void testHandleEvictMessageFromOtherInstance() {
    CacheInvalidationMessage message = CacheInvalidationMessage.evict("user:1", "other-instance");
    listener.handleMessage(message.toJson());
    verify(l1CacheManager).evict("user:1");
  }

  @Test
  void testHandleClearMessageFromOtherInstance() {
    CacheInvalidationMessage message = CacheInvalidationMessage.clear("other-instance");
    listener.handleMessage(message.toJson());
    verify(l1CacheManager).clear();
  }

  @Test
  void testIgnoreSelfPublishedEvictMessage() {
    CacheInvalidationMessage message = CacheInvalidationMessage.evict("user:1", INSTANCE_ID);
    listener.handleMessage(message.toJson());
    verifyNoInteractions(l1CacheManager);
  }

  @Test
  void testIgnoreSelfPublishedClearMessage() {
    CacheInvalidationMessage message = CacheInvalidationMessage.clear(INSTANCE_ID);
    listener.handleMessage(message.toJson());
    verifyNoInteractions(l1CacheManager);
  }

  @Test
  void testHandleInvalidJson() {
    // Should not throw, just log error
    listener.handleMessage("invalid json");
    verifyNoInteractions(l1CacheManager);
  }

  @Test
  void testHandleEmptyJson() {
    listener.handleMessage("{}");
    // Type is null, switch will go to default branch
    verifyNoInteractions(l1CacheManager);
  }

  @Test
  void testListenerCreation() {
    assertNotNull(new CacheInvalidationListener(l1CacheManager, INSTANCE_ID));
  }

  @Test
  void testHandleClearPrefixMessageFromOtherInstance() {
    CacheInvalidationMessage message = CacheInvalidationMessage.clearPrefix("user", "other-instance");
    listener.handleMessage(message.toJson());
    verify(l1CacheManager).clearByPrefix("user");
  }

  @Test
  void testIgnoreSelfPublishedClearPrefixMessage() {
    CacheInvalidationMessage message = CacheInvalidationMessage.clearPrefix("user", INSTANCE_ID);
    listener.handleMessage(message.toJson());
    verifyNoInteractions(l1CacheManager);
  }
}
