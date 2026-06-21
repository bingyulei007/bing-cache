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
 * null 值占位符标记接口.
 *
 * <p>缓存实现（如 Caffeine）不支持存储 null 值，需要用占位符替代。
 * 实现此接口的对象表示被缓存的原始值为 null。</p>
 *
 * <p>检测约定：通过 {@code instanceof NullValueSentinel} 判断，
 * 类型安全，不依赖类名字符串，重构友好。</p>
 */
public interface NullValueSentinel {
}
