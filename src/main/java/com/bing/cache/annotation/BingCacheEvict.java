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
   * <p>与 {@link BingCache#cacheName()} 指定相同值即可确保
   * 清除注解能匹配到缓存注解生成的 key。
   * 优先级高于 {@link #keyPrefix()}。</p>
   *
   * @return 缓存名称
   */
  String cacheName() default "";

  /**
   * 缓存 key 前缀.
   *
   * <p>为空时默认使用 "类全限定名.方法名" 作为前缀，
   * 与 {@link BingCache#keyPrefix()} 保持一致。
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
   * @return 参数索引数组
   */
  int[] argIndexes() default {};

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
