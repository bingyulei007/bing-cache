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

package com.bing.cache.aspect;

import com.bing.cache.cache.NullValueSentinel;

/**
 * null 值占位符.
 *
 * <p>Caffeine 不支持缓存 null 值，使用此占位符代替 null 存入缓存。
 * 读取时如果遇到此占位符，还原为 null 返回给调用方。</p>
 *
 * <p>该类为包级可见，无法被 Jackson 反序列化，因此不能存入 Redis。
 * 检测方式：通过 {@code instanceof NullValueSentinel} 判断，类型安全。</p>
 */
final class BingCacheNullValue implements NullValueSentinel {

  static final BingCacheNullValue INSTANCE = new BingCacheNullValue();

  private BingCacheNullValue() {
    // singleton
  }
}
