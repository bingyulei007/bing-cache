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
   * null 值缓存兜底 TTL（秒）.
   *
   * <p>当 {@code @BingCache(cacheNullValue=true)} 且 {@code expireTime<=0}（永不过期）时，
   * null 占位符（{@link BingCacheNullValue#INSTANCE}）使用此值作为过期时间。</p>
   *
   * <p><b>为何对 null 值单独兜底：</b>普通值 {@code expireTime=0}（永不过期）用于缓存字典、配置
   * 等"存在且不变"的数据是合理的；但 null 值表示"数据<b>当前</b>不存在"，是一个会过时的临时判定
   * （DB 随时可写使其变为"存在"）。让 null 占位符永不过期会把临时判定固化为永久结论，
   * 违背防穿透的初衷，导致 DB 后续插入数据后本实例持续读到 null（永久脏读）。</p>
   *
   * <p><b>模式对称性：</b>此兜底在纯 L1 和 L1+L2 模式下均生效。纯 L1 模式下原本无任何兜底
   * （{@code l1-max-ttl=0} 即不限制），null 占位符会永久驻留；L1+L2 模式下虽有
   * {@code l1-max-ttl} 默认 300s 兜底，但此处统一处理确保两种模式行为一致，
   * 且对 null 值语义独立于 {@code l1-max-ttl} 全局配置。取值与
   * {@code BingCacheAutoConfiguration.DEFAULT_L1_MAX_TTL_SECONDS} 对齐。</p>
   */
  static final long NULL_VALUE_FALLBACK_TTL_SECONDS = 300L;

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
        bingCache.group(), bingCache.cacheName(), bingCache.keyPrefix(),
        bingCache.argIndexes(), bingCache.argSpel());

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
      cacheManager.put(key, result, bingCache.expireTime(), bingCache.maxSize());
      LOG.debug("Cache put: {}", key);
    } else if (bingCache.cacheNullValue()) {
      // null 值缓存：expireTime<=0（永不过期）时使用兜底 TTL，避免 null 占位符永久驻留 L1。
      // null 表示"数据当前不存在"是临时判定，不应像字典/配置那样永久缓存。
      // （见 NULL_VALUE_FALLBACK_TTL_SECONDS 的 Javadoc）
      long nullExpire = bingCache.expireTime() > 0
          ? bingCache.expireTime()
          : NULL_VALUE_FALLBACK_TTL_SECONDS;
      if (bingCache.expireTime() <= 0) {
        warnNullValueFallback(method, bingCache.expireTime(), nullExpire);
      }
      cacheManager.put(key, BingCacheNullValue.INSTANCE, nullExpire, bingCache.maxSize());
      LOG.debug("Cache put (null value): {}, expireSeconds={}", key, nullExpire);
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

  /**
   * 当 null 值缓存触发兜底 TTL 时输出警告（每个方法仅一次）.
   *
   * <p>提示用户 {@code expireTime<=0} 的 null 占位符已被自动套用兜底 TTL，
   * 避免永久脏读。建议用户为 {@code cacheNullValue=true} 显式设置合理的
   * {@code expireTime}（如 60 秒），而非依赖兜底值。</p>
   *
   * @param method       目标方法
   * @param configured   用户配置的 expireTime（<=0，触发兜底）
   * @param fallback     实际使用的兜底 TTL（秒）
   */
  private void warnNullValueFallback(Method method, int configured, long fallback) {
    String methodKey = method.getDeclaringClass().getName() + "#" + method.getName()
        + "#nullValueFallback";
    if (warnedMethods.add(methodKey)) {
      LOG.warn("@BingCache on method '{}' has cacheNullValue=true but expireTime={} "
          + "(never expire). Null value sentinel would stay in L1 forever, causing permanent "
          + "stale reads after the data is later inserted. Falling back to {}s TTL. "
          + "Consider setting a positive expireTime for cacheNullValue=true methods.",
          method.getName(), configured, fallback);
    }
  }
}
