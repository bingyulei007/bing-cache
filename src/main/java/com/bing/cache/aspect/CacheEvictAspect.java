package com.bing.cache.aspect;

import com.bing.cache.annotation.BingCacheEvict;
import com.bing.cache.cache.CacheManager;
import com.bing.cache.util.CacheKeyGenerator;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  /** 已警告过的方法，避免重复输出 WARN 日志. */
  private static final Set<String> WARNED_METHODS = ConcurrentHashMap.newKeySet();

  private final CacheManager cacheManager;

  /**
   * 构造方法注入缓存管理器.
   *
   * @param cacheManager 缓存管理器
   */
  public CacheEvictAspect(CacheManager cacheManager) {
    this.cacheManager = cacheManager;
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
  @SuppressWarnings("checkstyle:MissingJavadocMethod")
  public Object around(ProceedingJoinPoint joinPoint, BingCacheEvict bingCacheEvict)
      throws Throwable {
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    Method method = signature.getMethod();
    Object[] args = joinPoint.getArgs();

    // 未指定 cacheName 或 keyPrefix 时，默认前缀为当前方法名，可能与 @BingCache 的方法名不匹配
    // 每个方法只警告一次，避免高频调用时日志风暴
    if ((bingCacheEvict.cacheName() == null || bingCacheEvict.cacheName().isEmpty())
        && (bingCacheEvict.keyPrefix() == null || bingCacheEvict.keyPrefix().isEmpty())) {
      String methodKey = method.getDeclaringClass().getName() + "#" + method.getName()
          + "(" + method.getParameterCount() + " params)";
      if (WARNED_METHODS.add(methodKey)) {
        LOG.warn("@BingCacheEvict on method '{}' has no cacheName or keyPrefix set. "
            + "The default prefix (this method name) may not match @BingCache's method name, "
            + "causing eviction to silently miss the cached key. "
            + "Consider setting cacheName to match @BingCache.", method.getName());
      }
    }

    // allEntries=true 时不需要生成 key，避免无意义的 key 生成和潜在的 argIndexes 越界异常
    String key = bingCacheEvict.allEntries() ? null : CacheKeyGenerator.generate(method, args,
        bingCacheEvict.cacheName(), bingCacheEvict.keyPrefix(),
        bingCacheEvict.argIndexes());

    if (bingCacheEvict.beforeInvocation()) {
      doEvict(bingCacheEvict, key);
      return joinPoint.proceed();
    } else {
      Object result = joinPoint.proceed();
      doEvict(bingCacheEvict, key);
      return result;
    }
  }

  /**
   * 执行缓存清除操作.
   *
   * <p>当 {@code allEntries = true} 时：
   * <ul>
   *   <li>有 cacheName/keyPrefix → 按前缀清除（{@code clearByPrefix}）</li>
   *   <li>都没有 → 全局清空（{@code clear}）</li>
   * </ul>
   *
   * @param bingCacheEvict 缓存清除注解
   * @param key            缓存 key（allEntries 且有前缀时忽略）
   */
  private void doEvict(BingCacheEvict bingCacheEvict, String key) {
    if (bingCacheEvict.allEntries()) {
      String prefix = resolvePrefix(bingCacheEvict);
      if (prefix != null && !prefix.isEmpty()) {
        cacheManager.clearByPrefix(prefix);
        LOG.debug("Cache clear by prefix: {}", prefix);
      } else {
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
   * <p>优先级：cacheName > keyPrefix。都为空时返回 null。</p>
   *
   * @param bingCacheEvict 缓存清除注解
   * @return 缓存前缀，都为空时返回 null
   */
  private String resolvePrefix(BingCacheEvict bingCacheEvict) {
    if (bingCacheEvict.cacheName() != null && !bingCacheEvict.cacheName().isEmpty()) {
      return bingCacheEvict.cacheName();
    }
    if (bingCacheEvict.keyPrefix() != null && !bingCacheEvict.keyPrefix().isEmpty()) {
      return bingCacheEvict.keyPrefix();
    }
    return null;
  }
}
