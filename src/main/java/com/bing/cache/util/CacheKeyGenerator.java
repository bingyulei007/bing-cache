package com.bing.cache.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * 缓存 Key 生成器.
 *
 * <p>根据方法签名和参数值生成唯一的缓存 key。
 * 参数序列化使用 Jackson ObjectMapper，确保自定义对象的 key 生成
 * 不依赖 {@code toString()} 实现，在不同实例和 JVM 重启后保持一致。</p>
 *
 * <p>生成的 key 最大长度为 {@value #MAX_KEY_LENGTH} 个字符，
 * 超过时截断参数部分并追加哈希后缀以保证唯一性。</p>
 */
public final class CacheKeyGenerator {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /** 缓存 key 最大长度. */
  static final int MAX_KEY_LENGTH = 256;

  private CacheKeyGenerator() {
    // utility class
  }

  /**
   * 生成缓存 key.
   *
   * <p>格式：前缀(参数1,参数2,...)
   * 前缀优先级：cacheName > keyPrefix > 类全限定名.方法名。
   * 如果提供 argIndexes，则只有指定索引的参数参与 key 生成。
   * 参数值通过 Jackson 序列化为字符串，确保自定义对象的 key 确定性。</p>
   *
   * @param method     目标方法
   * @param args       方法参数
   * @param cacheName  缓存名称，用于读写注解共享同一前缀，可为空
   * @param keyPrefix  自定义前缀，可为空
   * @param argIndexes 参与 key 生成的参数索引，为空表示全部参数
   * @return 缓存 key
   */
  public static String generate(Method method, Object[] args,
      String cacheName, String keyPrefix, int[] argIndexes) {
    String className = method.getDeclaringClass().getName();
    String methodName = method.getName();
    String prefix;
    if (cacheName != null && !cacheName.isEmpty()) {
      prefix = cacheName;
    } else if (keyPrefix != null && !keyPrefix.isEmpty()) {
      prefix = keyPrefix;
    } else {
      prefix = className + "." + methodName;
    }

    Object[] keyArgs = filterArgs(args, argIndexes);
    String argsStr = serializeArgs(keyArgs);
    String key = prefix + "(" + argsStr + ")";
    return truncateKey(key);
  }

  /**
   * 截断过长的 key.
   *
   * <p>当 key 长度超过 {@link #MAX_KEY_LENGTH} 时，截断到最大长度并追加
   * 原始 key 的 SHA-256 哈希后缀（64 位），保证截断后的 key 仍然唯一。</p>
   *
   * @param key 原始 key
   * @return 截断后的 key（如未超长则原样返回）
   */
  private static String truncateKey(String key) {
    if (key.length() <= MAX_KEY_LENGTH) {
      return key;
    }
    String hash = sha256Hex(key);
    String suffix = "...#" + hash.substring(0, 16);
    int truncatedLength = MAX_KEY_LENGTH - suffix.length();
    return key.substring(0, truncatedLength) + suffix;
  }

  /**
   * 计算字符串的 SHA-256 哈希（十六进制）.
   *
   * @param input 输入字符串
   * @return 64 字符的十六进制哈希字符串
   */
  private static String sha256Hex(String input) {
    try {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed to be available in all JVMs
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  /**
   * 将参数数组序列化为字符串.
   *
   * <p>对于基本类型和字符串直接使用 {@link String#valueOf(Object)}；
   * 对于数组使用 {@link Arrays#deepToString(Object[])}；
   * 对于其他自定义对象使用 Jackson 序列化为 JSON，
   * 确保不依赖 {@code toString()} 实现也能生成确定性 key。</p>
   *
   * @param args 参数数组
   * @return 序列化后的字符串
   */
  private static String serializeArgs(Object[] args) {
    if (args == null || args.length == 0) {
      return "[]";
    }
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < args.length; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(serializeArg(args[i]));
    }
    sb.append("]");
    return sb.toString();
  }

  /**
   * 序列化单个参数.
   *
   * @param arg 参数值
   * @return 序列化后的字符串
   */
  private static String serializeArg(Object arg) {
    if (arg == null) {
      return "null";
    }
    // 基本类型和字符串，直接用 String.valueOf
    if (arg instanceof Number || arg instanceof Boolean || arg instanceof Character
        || arg instanceof String) {
      return String.valueOf(arg);
    }
    // 对象数组类型，使用 Arrays.deepToString
    if (arg instanceof Object[]) {
      return Arrays.deepToString((Object[]) arg);
    }
    // 基本类型数组 (int[], byte[] 等)，包装后使用 deepToString
    if (arg.getClass().isArray()) {
      return Arrays.deepToString(new Object[]{arg});
    }
    // 自定义对象，使用 Jackson 序列化确保确定性
    try {
      return OBJECT_MAPPER.writeValueAsString(arg);
    } catch (JsonProcessingException e) {
      // Jackson 序列化失败时不可静默降级为 hashCode：
      // 未重写 hashCode 的对象走 identity hashCode，每次 JVM 启动都不同，
      // 会破坏"重启后 key 一致"的承诺，导致缓存全部失效且难以排查。
      // 直接抛异常让调用方感知并修复参数类型（如注册 JavaTime 模块、避免循环引用）。
      throw new IllegalStateException(
          "Failed to serialize argument of type " + arg.getClass().getName()
              + " for cache key generation. Jackson error: " + e.getOriginalMessage(), e);
    }
  }

  /**
   * 根据索引过滤参数.
   *
   * @param args       原始参数数组
   * @param argIndexes 索引数组，为空时返回全部参数
   * @return 过滤后的参数数组
   * @throws IllegalArgumentException 如果 argIndexes 中有越界索引
   */
  private static Object[] filterArgs(Object[] args, int[] argIndexes) {
    if (args == null || args.length == 0) {
      return new Object[0];
    }
    if (argIndexes == null || argIndexes.length == 0) {
      return args;
    }
    Object[] filtered = new Object[argIndexes.length];
    for (int i = 0; i < argIndexes.length; i++) {
      int idx = argIndexes[i];
      if (idx < 0 || idx >= args.length) {
        throw new IllegalArgumentException(
            "argIndexes[" + i + "]=" + idx + " is out of range, args length=" + args.length);
      }
      filtered[i] = args[idx];
    }
    return filtered;
  }
}
