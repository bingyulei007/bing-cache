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
 * 缓存清除注解.
 *
 * <p>标注在方法上，自动清除对应的缓存条目。
 * 典型用法：在更新/删除方法上标注，确保缓存与数据源一致。</p>
 *
 * <p>默认行为：方法成功执行后清除指定 key 的缓存。
 * 可通过 {@link #allEntries()} 清空所有缓存，
 * 或通过 {@link #beforeInvocation()} 在方法执行前清除。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BingCacheEvict {

  /**
   * 缓存名称，用于读写注解共享同一前缀.
   *
   * <p>与 {@link BingCache#cacheName()} 指定相同值可确保缓存前缀一致。
   * 要精确清除缓存，参数部分（{@link #argIndexes()} 或 {@link #argSpel()}）
   * 也必须与对应的 {@link BingCache} 保持一致，否则生成的 key 不匹配。
   * 优先级高于 {@link #keyPrefix()}。</p>
   *
   * <p><b>重载方法注意：</b>若 {@link BingCache} 一侧存在同名重载方法，
   * 应保证 evict 的参数选取（argIndexes/argSpel）与对应的 {@link BingCache} 完全一致；
   * 否则在 cacheName 相同时生成的 key 可能错配，导致误删或漏删。</p>
   *
   * @return 缓存名称
   */
  String cacheName() default "";

  /**
   * 缓存 key 前缀.
   *
   * <p>为空时默认使用 {@code className.methodName(paramTypes)} 作为前缀
   *（含参数类型签名），与 {@link BingCache#keyPrefix()} 保持一致。
   * 当 {@link #cacheName()} 不为空时，cacheName 优先。</p>
   *
   * @return key 前缀
   */
  String keyPrefix() default "";

  /**
   * 参与缓存 key 生成的参数索引.
   *
   * <p>默认为空数组，表示所有参数都参与 key 生成，
   * 与 {@link BingCache#argIndexes()} 保持一致。</p>
   *
   * <p>当 {@link #argSpel()} 非空时，此属性被忽略。</p>
   *
   * @return 参数索引数组
   */
  int[] argIndexes() default {};

  /**
   * SpEL 表达式，用于从方法参数中选取值参与 key 生成.
   *
   * <p>非空时优先于 {@link #argIndexes()}，表达式求值结果作为 key 的参数部分。
   * 应与对应的 {@link BingCache#argSpel()} 保持一致，确保 evict 能匹配到缓存 key。</p>
   *
   * <p>表达式中可用的变量与 {@link BingCache#argSpel()} 相同（类似 Spring
   * {@code @Cacheable} 的参数变量），包括 {@code #参数名}、{@code #p0} / {@code #a0}、
   * {@code #root.method}、{@code #root.methodName}、{@code #root.args} 和
   * {@code #root.target}。</p>
   *
   * <p>注意：不支持 {@code #root.targetClass}、{@code #caches} 等 Spring Cache 特有变量。
   * {@code allEntries=true} 时此属性不生效。</p>
   *
   * @return SpEL 表达式，为空时使用 argIndexes
   */
  String argSpel() default "";

  /**
   * 是否清空所有缓存.
   *
   * <p>默认 {@code false}，仅清除指定 key 的缓存。
   * 设置为 {@code true} 时：</p>
   * <ul>
   *   <li>指定了 {@link #cacheName()} 或 {@link #keyPrefix()} 时，按前缀清除（{@code clearByPrefix}）</li>
   *   <li>都未指定时，全局清空（{@code clear}）</li>
   * </ul>
   * <p>{@link #argIndexes()} 在 {@code allEntries=true} 时不生效。</p>
   *
   * @return 是否清空所有缓存
   */
  boolean allEntries() default false;

  /**
   * 是否在方法执行前清除缓存.
   *
   * <p>默认 {@code false}，方法成功执行后才清除缓存。
   * 设置为 {@code true} 时，先清除缓存再执行方法，
   * 确保即使方法抛异常，缓存也会被清除。</p>
   *
   * @return 是否在方法执行前清除
   */
  boolean beforeInvocation() default false;
}
