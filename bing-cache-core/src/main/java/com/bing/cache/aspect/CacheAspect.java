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

import com.bing.cache.annotation.BingCache;
import com.bing.cache.cache.CacheManager;
import com.bing.cache.cache.NullValueSentinel;
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
 * 缓存切面.
 *
 * <p>拦截带有 {@link BingCache} 注解的方法，实现缓存查询和写入逻辑。
 * 当 {@link BingCache#cacheNullValue()} 为 {@code true} 时，
 * 使用 {@link BingCacheNullValue#INSTANCE} 占位符（实现 {@link NullValueSentinel}）
 * 缓存 null 结果，防止缓存穿透。</p>
 */
@Aspect
public class CacheAspect {

  private static final Logger LOG = LoggerFactory.getLogger(CacheAspect.class);

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
  public CacheAspect(CacheManager cacheManager, CacheKeyGenerator cacheKeyGenerator) {
    this.cacheManager = cacheManager;
    this.cacheKeyGenerator = cacheKeyGenerator;
  }

  /**
   * 环绕通知，拦截 @BingCache 注解方法.
   *
   * @param joinPoint 连接点
   * @param bingCache 缓存注解
   * @return 方法执行结果
   * @throws Throwable 方法执行异常
   */
  @Around("@annotation(bingCache)")
  public Object around(ProceedingJoinPoint joinPoint, BingCache bingCache) throws Throwable {
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    Method method = signature.getMethod();
    Object[] args = joinPoint.getArgs();
    Object target = joinPoint.getTarget();

    warnIfKeyAndArgIndexesConflict(bingCache, method);

    String key = cacheKeyGenerator.generate(method, args, target,
        bingCache.cacheName(), bingCache.keyPrefix(), bingCache.argIndexes(), bingCache.argSpel());

    // try cache
    Object cached = cacheManager.get(key);
    if (cached != null) {
      // null 值占位符 → 返回 null
      if (cached instanceof NullValueSentinel) {
        LOG.debug("Cache hit (null sentinel): {}", key);
        return null;
      }
      LOG.debug("Cache hit: {}", key);
      return cached;
    }

    // cache miss, execute method
    LOG.debug("Cache miss: {}", key);
    Object result = joinPoint.proceed();

    // cache result
    if (result != null) {
      cacheManager.put(key, result, bingCache.expireTime());
      LOG.debug("Cache put: {}", key);
    } else if (bingCache.cacheNullValue()) {
      cacheManager.put(key, BingCacheNullValue.INSTANCE, bingCache.expireTime());
      LOG.debug("Cache put (null value): {}", key);
    } else {
      LOG.debug("Cache skip (null result): {}", key);
    }

    return result;
  }

  /**
   * 当 argSpel 和 argIndexes 同时设置时输出警告.
   *
   * @param bingCache 缓存注解
   * @param method    目标方法
   */
  private void warnIfKeyAndArgIndexesConflict(BingCache bingCache, Method method) {
    if (StringUtils.hasText(bingCache.argSpel())
        && bingCache.argIndexes() != null && bingCache.argIndexes().length > 0) {
      String methodKey = method.getDeclaringClass().getName() + "#" + method.getName()
          + "#keyConflict";
      if (warnedMethods.add(methodKey)) {
        LOG.warn("@BingCache on method '{}' has both argSpel() and argIndexes() set. "
            + "argSpel (SpEL) takes precedence; argIndexes will be ignored.",
            method.getName());
      }
    }
  }
}
