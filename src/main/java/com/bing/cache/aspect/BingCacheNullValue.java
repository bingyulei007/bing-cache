package com.bing.cache.aspect;

/**
 * null 值占位符.
 *
 * <p>Caffeine 不支持缓存 null 值，使用此占位符代替 null 存入缓存。
 * 读取时如果遇到此占位符，还原为 null 返回给调用方。</p>
 *
 * <p>该类为包级可见，无法被 Jackson 反序列化，因此不能存入 Redis。
 * {@link com.bing.cache.cache.CompositeCacheManager} 通过
 * {@link #isBingCacheNullValue(Object)} 检测并跳过 L2 存储。</p>
 */
final class BingCacheNullValue {

  static final BingCacheNullValue INSTANCE = new BingCacheNullValue();

  private BingCacheNullValue() {
    // singleton
  }

  /**
   * 检查对象是否为 BingCacheNullValue 占位符.
   *
   * <p>使用类名匹配而非 instanceof，因为该类为包私有，
   * 其他包（如 CompositeCacheManager）无法直接引用。</p>
   *
   * @param obj 待检查的对象
   * @return true 如果是 BingCacheNullValue 占位符
   */
  static boolean isBingCacheNullValue(Object obj) {
    return obj != null && "BingCacheNullValue".equals(obj.getClass().getSimpleName());
  }
}
