package com.bing.cache.cache;

/**
 * null 值占位符标记接口.
 *
 * <p>缓存实现（如 Caffeine）不支持存储 null 值，需要用占位符替代。
 * 实现此接口的对象表示被缓存的原始值为 null。</p>
 *
 * <p>检测约定：通过 {@code instanceof NullValueSentinel} 判断，
 * 类型安全，不依赖类名字符串，重构友好。</p>
 */
public interface NullValueSentinel {
}
