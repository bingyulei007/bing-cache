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

/**
 * 缓存失效通知发布接口.
 *
 * <p>用于在缓存驱逐或清除时通知其他实例，
 * 实现可基于 Redis Pub/Sub 等机制。</p>
 */
public interface CacheInvalidationPublisher {

  /**
   * 发布单 key 驱逐通知.
   *
   * @param key 需要驱逐的缓存 key
   */
  void publishEvict(String key);

  /**
   * 发布全量清除通知.
   */
  void publishClear();

  /**
   * 发布按前缀清除通知.
   *
   * @param prefix 需要清除的缓存 key 前缀
   */
  void publishClearByPrefix(String prefix);

  /**
   * 发布按分组清除通知.
   *
   * @param group 需要清除的缓存分组名称
   */
  void publishClearByGroup(String group);
}
