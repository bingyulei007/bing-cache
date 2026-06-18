package com.bing.cache.aspect;

import com.bing.cache.cache.NullValueSentinel;

/**
 * null 值占位符.
 *
 * <p>Caffeine 不支持缓存 null 值，使用此占位符代替 null 存入缓存。
 * 读取时如果遇到此占位符，还原为 null 返回给调用方。</p>
 *
 * <p>该类为包级可见，无法被 Jackson 反序列化，因此不能存入 Redis。
 * 检测方式：通过 {@code instanceof NullValueSentinel} 判断，类型安全。</p>
 */
final class BingCacheNullValue implements NullValueSentinel {

  static final BingCacheNullValue INSTANCE = new BingCacheNullValue();

  private BingCacheNullValue() {
    // singleton
  }
}
