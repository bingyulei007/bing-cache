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

package com.bing.cache.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link BingCacheEvict} 的容器注解，支持同一个方法上重复使用 {@code @BingCacheEvict}.
 *
 * <p>Java 不允许直接重复同一个注解，通过 {@code @Repeatable} 机制，
 * 编译器会自动将多个 {@code @BingCacheEvict} 包装为 {@code @BingCacheEvicts}。</p>
 *
 * <p>使用示例：</p>
 * <pre>
 * // 更新用户 — 同时清除账号详情和订单列表
 * &#64;BingCacheEvict(cacheName = "userAccount", argIndexes = {0})
 * &#64;BingCacheEvict(cacheName = "userOrders", argIndexes = {0})
 * public void updateUser(Long id, UserVO vo) { ... }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BingCacheEvicts {

  /**
   * 多个 {@link BingCacheEvict} 注解数组.
   *
   * @return BingCacheEvict 数组
   */
  BingCacheEvict[] value();
}
