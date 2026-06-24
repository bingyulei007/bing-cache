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
 * 方法级缓存注解.
 *
 * <p>标注在方法上，自动对方法结果进行本地缓存。
 * 缓存命中时直接返回缓存值，跳过方法执行；
 * 未命中时执行方法并将结果存入缓存。</p>
 *
 * <p><b>默认前缀与重载方法：</b>当未显式指定 {@link #cacheName()} 或 {@link #keyPrefix()}
 * 时，默认前缀格式为 {@code className.methodName(paramTypes)}（含参数类型签名）。
 * 例如 {@code findById(Long)} 的默认前缀为
 * {@code "com.example.Foo.findById(java.lang.Long)"}。
 * 参数类型签名可确保同名重载方法（如 {@code findById(Long)} 与 {@code findById(String)}）
 * 生成不同的缓存 key，避免碰撞。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BingCache {

  /**
   * 缓存分组名称，用于将相关缓存归类.
   *
   * <p>group 作为缓存 key 的最外层前缀，支持按组批量清除。
   * 不能单独使用，必须配合 {@link #cacheName()} 或 {@link #keyPrefix()}。
   * allEntries=true 时 group 可单独使用，触发 {@code clearByGroup(group)}。</p>
   *
   * @return 缓存分组名称
   */
  String group() default "";

  /**
   * 缓存名称，用于读写注解共享同一前缀.
   *
   * <p>当 {@code @BingCache} 和 {@code @BingCacheEvict} 标注在不同方法上时，
   * 指定相同的 cacheName 即可确保两者操作同一组缓存条目。
   * 优先级高于 {@link #keyPrefix()}。</p>
   *
   * @return 缓存名称
   */
  String cacheName() default "";

  /**
   * 缓存 key 前缀.
   *
   * <p>为空时默认使用 {@code className.methodName(paramTypes)} 作为前缀
   *（含参数类型签名，详见类级 Javadoc）。
   * 设置后将替换默认前缀，用于自定义缓存空间。
   * 当 {@link #cacheName()} 不为空时，cacheName 优先。</p>
   *
   * @return key 前缀
   */
  String keyPrefix() default "";

  /**
   * 缓存过期时间（秒）.
   *
   * <p>默认值 0 表示不过期。
   * 设置为正数时，缓存条目在指定秒数后自动淘汰。</p>
   *
   * <p>在 L1+L2 模式下，若 {@code bing.cache.caffeine.l1-max-ttl} 配置了 L1 最大存活时间，
   * 实际 L1 过期时间为 {@code min(expireTime, l1MaxTtl)}，
   * 作为 Pub/Sub 丢失或 Redis 不可用时的兜底保障；L2 过期时间仍按本值。</p>
   *
   * @return 过期秒数
   */
  int expireTime() default 0;

  /**
   * 参与缓存 key 生成的参数索引.
   *
   * <p>默认为空数组，表示所有参数都参与 key 生成。
   * 设置后只有指定索引位置的参数值会被包含在 key 中。
   * 例如 {0, 2} 表示只使用第 1 个和第 3 个参数。</p>
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
   * 前缀仍由 {@link #cacheName()} 或 {@link #keyPrefix()} 决定。</p>
   *
   * <p>表达式中可用的变量（类似 Spring {@code @Cacheable} 的参数变量）：</p>
   * <ul>
   *   <li>{@code #参数名} — 按名称引用方法参数，如 {@code #user.id}</li>
   *   <li>{@code #p0}, {@code #a0} — 按索引引用方法参数</li>
   *   <li>{@code #root.method} — 当前方法（{@code Method} 对象）</li>
   *   <li>{@code #root.methodName} — 方法名</li>
   *   <li>{@code #root.args} — 参数数组</li>
   *   <li>{@code #root.target} — 目标对象</li>
   * </ul>
   *
   * <p><b>单值与多值：</b></p>
   * <ul>
   *   <li>单值表达式：如 {@code #user.id}、{@code #userId + ':' + #type}、
   *       {@code T(Collections).singletonList(#a)}、{@code {#list}}。
   *       单值输出 {@code Sg[...]}，其中 {@code Sg} 表示 single。</li>
   *   <li>多值表达式：使用 SpEL 列表字面量 {@code {expr1, expr2, ...}}，顶层逗号分隔，
   *       嵌套括号/数组/字符串里的逗号被忽略。输出 {@code [...]}，与
   *       {@code argIndexes} / 默认多参数的格式一致。</li>
   * </ul>
   *
   * <p>注意：不支持 {@code #root.targetClass}、{@code #caches} 等 Spring Cache 特有变量。</p>
   *
   * <p>示例：</p>
   * <ul>
   *   <li>{@code argSpel = "#user.id"} → 单值，key 形如 {@code cacheName(Sg[N:1])}</li>
   *   <li>{@code argSpel = "{#a, #b}"} → 多值，key 形如 {@code cacheName([N:1, N:2])}</li>
   *   <li>{@code argSpel = "{#list}"} → 单值（去壳），key 形如 {@code cacheName(Sg[[N:1, N:2]])}</li>
   * </ul>
   *
   * <p>求值结果为 null 时序列化为 {@code "null"}，自定义对象使用 Jackson 序列化。</p>
   *
   * @return SpEL 表达式，为空时使用 argIndexes
   */
  String argSpel() default "";

  /**
   * 是否缓存 null 结果.
   *
   * <p>默认 {@code false}，方法返回 null 时不缓存，每次都会重新执行方法。
   * 设置为 {@code true} 时，null 结果也会被缓存，可以防止缓存穿透
   * （大量请求查询不存在的数据导致频繁访问数据库）。</p>
   *
   * @return 是否缓存 null 结果
   */
  boolean cacheNullValue() default false;
}
