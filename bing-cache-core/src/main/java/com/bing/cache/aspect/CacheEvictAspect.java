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

import com.bing.cache.annotation.BingCacheEvict;
import com.bing.cache.annotation.BingCacheEvicts;
import com.bing.cache.cache.CacheManager;
import com.bing.cache.util.CacheKeyGenerator;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 缓存清除切面.
 *
 * <p>拦截带有 {@link BingCacheEvict} 注解的方法，实现缓存清除逻辑。
 * 支持按 key 清除和清空所有缓存，支持方法执行前/后清除。</p>
 */
@Aspect
public class CacheEvictAspect {

  private static final Logger LOG = LoggerFactory.getLogger(CacheEvictAspect.class);

  /**
   * 已警告过的方法，避免重复输出 WARN 日志.
   *
   * <p>实例字段（非 static），避免同一 JVM 内多个 Spring 上下文（如集成测试场景）
   * 共享此集合导致后续上下文不再输出警告日志。</p>
   */
  private final Set<String> warnedMethods = ConcurrentHashMap.newKeySet();

  private final CacheManager cacheManager;

  private final CacheKeyGenerator cacheKeyGenerator;

  /**
   * 构造方法注入缓存管理器和 key 生成器.
   *
   * @param cacheManager      缓存管理器
   * @param cacheKeyGenerator 缓存 key 生成器
   */
  public CacheEvictAspect(CacheManager cacheManager, CacheKeyGenerator cacheKeyGenerator) {
    this.cacheManager = cacheManager;
    this.cacheKeyGenerator = cacheKeyGenerator;
  }

  /**
   * 环绕通知，拦截 @BingCacheEvict 注解方法.
   *
   * @param joinPoint       连接点
   * @param bingCacheEvict 缓存清除注解
   * @return 方法执行结果
   * @throws Throwable 方法执行异常
   */
  @Around("@annotation(bingCacheEvict)")
  public Object around(ProceedingJoinPoint joinPoint, BingCacheEvict bingCacheEvict)
      throws Throwable {
    return doAround(joinPoint, new BingCacheEvict[]{bingCacheEvict});
  }

  /**
   * 环绕通知，拦截多个 @BingCacheEvict 注解方法.
   *
   * <p>当方法上标注了多个 {@code @BingCacheEvict} 时，
   * Java 编译器会自动将它们包装在 {@code @BingCacheEvicts} 容器中。</p>
   *
   * @param joinPoint        连接点
   * @param bingCacheEvicts 缓存清除注解容器
   * @return 方法执行结果
   * @throws Throwable 方法执行异常
   */
  @Around("@annotation(bingCacheEvicts)")
  public Object aroundMultiple(ProceedingJoinPoint joinPoint, BingCacheEvicts bingCacheEvicts)
      throws Throwable {
    return doAround(joinPoint, bingCacheEvicts.value());
  }

  /**
   * 执行环绕通知逻辑.
   *
   * @param joinPoint       连接点
   * @param evictAnnotations 缓存清除注解数组（单个或多个）
   * @return 方法执行结果
   * @throws Throwable 方法执行异常
   */
  private Object doAround(ProceedingJoinPoint joinPoint, BingCacheEvict[] evictAnnotations)
      throws Throwable {
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    Method method = signature.getMethod();
    Object[] args = joinPoint.getArgs();
    Object target = joinPoint.getTarget();

    // 检查每个注解的配置冲突（每个方法只警告一次）
    for (BingCacheEvict bingCacheEvict : evictAnnotations) {
      warnIfMissingPrefix(bingCacheEvict, method);
      warnIfKeyAndArgIndexesConflict(bingCacheEvict, method);
      warnIfArgIndexesEmptyWithMultipleParams(bingCacheEvict, method);
    }

    // 检查是否有 beforeInvocation=true 的注解
    boolean hasBeforeInvocation = false;
    for (BingCacheEvict bingCacheEvict : evictAnnotations) {
      if (bingCacheEvict.beforeInvocation()) {
        hasBeforeInvocation = true;
        break;
      }
    }

    if (hasBeforeInvocation) {
      // beforeInvocation: 先清除缓存再执行方法
      for (BingCacheEvict bingCacheEvict : evictAnnotations) {
        if (bingCacheEvict.beforeInvocation()) {
          doSingleEvict(bingCacheEvict, method, args, target);
        }
      }
      Object result = joinPoint.proceed();
      // 方法执行后清除剩余的（非 beforeInvocation 的）
      for (BingCacheEvict bingCacheEvict : evictAnnotations) {
        if (!bingCacheEvict.beforeInvocation()) {
          doSingleEvict(bingCacheEvict, method, args, target);
        }
      }
      return result;
    } else {
      // 默认: 方法执行后再清除缓存
      Object result = joinPoint.proceed();
      for (BingCacheEvict bingCacheEvict : evictAnnotations) {
        doSingleEvict(bingCacheEvict, method, args, target);
      }
      return result;
    }
  }

  /**
   * 执行单个缓存清除操作.
   *
   * @param bingCacheEvict 缓存清除注解
   * @param method         目标方法
   * @param args           方法参数
   * @param target         目标对象
   */
  private void doSingleEvict(BingCacheEvict bingCacheEvict, Method method,
      Object[] args, Object target) {
    // allEntries=true 时不需要生成 key
    String key = bingCacheEvict.allEntries() ? null
        : cacheKeyGenerator.generate(method, args, target,
            bingCacheEvict.group(), bingCacheEvict.cacheName(), bingCacheEvict.keyPrefix(),
            bingCacheEvict.argIndexes(), bingCacheEvict.argSpel());
    doEvict(bingCacheEvict, key);
  }

  /**
   * 未指定 cacheName 或 keyPrefix 时输出警告.
   *
   * <p>当 group 非空且 allEntries=true 时跳过警告，
   * 因为此时走 clearByGroup 分支，不需要 cacheName/keyPrefix。</p>
   */
  private void warnIfMissingPrefix(BingCacheEvict bingCacheEvict, Method method) {
    if ((bingCacheEvict.cacheName() == null || bingCacheEvict.cacheName().isEmpty())
        && (bingCacheEvict.keyPrefix() == null || bingCacheEvict.keyPrefix().isEmpty())) {
      // group + allEntries=true 是合法用法（走 clearByGroup），不需要 cacheName/keyPrefix
      String group = bingCacheEvict.group();
      if (group != null && !group.isEmpty() && bingCacheEvict.allEntries()) {
        return;
      }
      String methodKey = method.getDeclaringClass().getName() + "#" + method.getName()
          + "(" + method.getParameterCount() + " params)";
      if (warnedMethods.add(methodKey)) {
        LOG.warn("@BingCacheEvict on method '{}' has no cacheName or keyPrefix set. "
            + "The default prefix (this method name) may not match @BingCache's method name, "
            + "causing eviction to silently miss the cached key. "
            + "Consider setting cacheName to match @BingCache.", method.getName());
      }
    }
  }

  /**
   * argSpel 和 argIndexes 同时设置时输出警告.
   */
  private void warnIfKeyAndArgIndexesConflict(BingCacheEvict bingCacheEvict, Method method) {
    if (!bingCacheEvict.allEntries()
        && StringUtils.hasText(bingCacheEvict.argSpel())
        && bingCacheEvict.argIndexes() != null && bingCacheEvict.argIndexes().length > 0) {
      String methodKey = method.getDeclaringClass().getName() + "#" + method.getName()
          + "#keyConflict";
      if (warnedMethods.add(methodKey)) {
        LOG.warn("@BingCacheEvict on method '{}' has both argSpel() and argIndexes() set. "
            + "argSpel (SpEL) takes precedence; argIndexes will be ignored.",
            method.getName());
      }
    }
  }

  /**
   * argIndexes 为空且方法有多个参数时输出警告.
   */
  private void warnIfArgIndexesEmptyWithMultipleParams(BingCacheEvict bingCacheEvict,
      Method method) {
    if (!bingCacheEvict.allEntries()
        && !StringUtils.hasText(bingCacheEvict.argSpel())
        && (bingCacheEvict.argIndexes() == null || bingCacheEvict.argIndexes().length == 0)
        && method.getParameterCount() > 1) {
      String methodKey = method.getDeclaringClass().getName() + "#" + method.getName()
          + "#argIndexes";
      if (warnedMethods.add(methodKey)) {
        LOG.warn("@BingCacheEvict on method '{}' has {} parameters but argIndexes is empty "
            + "(default: all parameters participate in key generation). "
            + "If the corresponding @BingCache uses argIndexes to select specific parameters, "
            + "the generated keys will NOT match and eviction will silently fail. "
            + "Set argIndexes on @BingCacheEvict to match @BingCache.",
            method.getName(), method.getParameterCount());
      }
    }
  }

  /**
   * 执行缓存清除操作.
   *
   * <p>当 {@code allEntries = true} 时：
   * <ul>
   *   <li>group + cacheName/keyPrefix → 按前缀清除（{@code clearByPrefix}）</li>
   *   <li>仅 group（无 cacheName/keyPrefix）→ 按组清除（{@code clearByGroup}）</li>
   *   <li>都没有 → 全局清空（{@code clear}）</li>
   * </ul>
   *
   * @param bingCacheEvict 缓存清除注解
   * @param key            缓存 key（allEntries 时忽略）
   */
  private void doEvict(BingCacheEvict bingCacheEvict, String key) {
    if (bingCacheEvict.allEntries()) {
      String group = bingCacheEvict.group();
      boolean hasGroup = group != null && !group.isEmpty();
      String prefix = resolvePrefix(bingCacheEvict);

      if (hasGroup && prefix == null) {
        // allEntries=true + 仅 group（无 cacheName/keyPrefix）→ clearByGroup
        CacheKeyGenerator.validateReservedName("group", group);
        cacheManager.clearByGroup(group);
        LOG.debug("Cache clear by group: {}", group);
      } else if (prefix != null && !prefix.isEmpty()) {
        // allEntries=true + cacheName/keyPrefix（可能带 group 前缀）→ clearByPrefix
        cacheManager.clearByPrefix(prefix);
        LOG.debug("Cache clear by prefix: {}", prefix);
      } else {
        // 全空 → 全局清空
        cacheManager.clear();
        LOG.debug("Cache clear all entries");
      }
    } else {
      cacheManager.evict(key);
      LOG.debug("Cache evict: {}", key);
    }
  }

  /**
   * 从注解中解析缓存前缀.
   *
   * <p>优先级：cacheName > keyPrefix。都为空时返回 null。
   * 当 group 非空且有 basePrefix 时，返回 {@code group:basePrefix}。</p>
   *
   * @param bingCacheEvict 缓存清除注解
   * @return 缓存前缀（含 group 拼接），都为空时返回 null
   */
  private String resolvePrefix(BingCacheEvict bingCacheEvict) {
    String group = bingCacheEvict.group();
    String cacheName = bingCacheEvict.cacheName();
    String keyPrefix = bingCacheEvict.keyPrefix();

    // 优先 cacheName，其次 keyPrefix
    String basePrefix;
    if (cacheName != null && !cacheName.isEmpty()) {
      basePrefix = cacheName;
    } else if (keyPrefix != null && !keyPrefix.isEmpty()) {
      basePrefix = keyPrefix;
    } else {
      basePrefix = null;
    }

    // group 作为最外层前缀拼接：group:basePrefix
    if (group != null && !group.isEmpty() && basePrefix != null) {
      return group + ":" + basePrefix;
    }
    return basePrefix;
  }
}
