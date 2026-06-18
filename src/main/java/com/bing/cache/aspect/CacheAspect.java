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

import java.lang.reflect.Method;

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

  private final CacheManager cacheManager;

  /**
   * 构造方法注入缓存管理器.
   *
   * @param cacheManager 缓存管理器
   */
  public CacheAspect(CacheManager cacheManager) {
    this.cacheManager = cacheManager;
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
  @SuppressWarnings("checkstyle:MissingJavadocMethod")
  public Object around(ProceedingJoinPoint joinPoint, BingCache bingCache) throws Throwable {
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    Method method = signature.getMethod();
    Object[] args = joinPoint.getArgs();

    String key = CacheKeyGenerator.generate(method, args,
        bingCache.cacheName(), bingCache.keyPrefix(), bingCache.argIndexes());

    // try cache
    Object cached = cacheManager.get(key);
    if (cached != null) {
      LOG.debug("Cache hit: {}", key);
      // null 值占位符 → 返回 null
      if (cached instanceof NullValueSentinel) {
        return null;
      }
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
}
