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
