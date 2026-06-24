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

package com.bing.cache.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.util.StringUtils;

/**
 * 缓存 Key 生成器.
 *
 * <p>根据方法签名和参数值生成唯一的缓存 key。
 * 支持两种参数选取方式：</p>
 * <ul>
 *   <li>SpEL 表达式（{@code keyExpression} 非空）：通过表达式从参数中选取值，如 {@code #user.id}</li>
 *   <li>参数索引（{@code argIndexes}）：按位置选取整个参数，SpEL 为空时使用</li>
 * </ul>
 *
 * <p><b>单值与多值：</b></p>
 * <ul>
 *   <li>单值选取（1 个参数）输出 {@code Sg[...]}，如 {@code Sg[N:1]}、{@code Sg[S:abc]}；
 *       单参数的花括号表达式（如 {@code {#list}}）会被去壳，等价于 {@code #list}</li>
 *   <li>多值选取（>=2 个参数）输出 {@code [...]}，如 {@code [N:1, N:2]}；
 *       使用 SpEL 列表字面量 {@code {expr1, expr2, ...}}，顶层逗号才作为分隔，
 *       嵌套 {@code ()}/{@code []}/{@code {}} 和字符串字面量中的逗号被忽略</li>
 * </ul>
 *
 * <p>参数序列化使用 Jackson ObjectMapper，确保自定义对象的 key 生成
 * 不依赖 {@code toString()} 实现，在不同实例和 JVM 重启后保持一致。</p>
 *
 * <p>生成的 key 最大长度为 {@value #MAX_KEY_LENGTH} 个字符，
 * 超过时截断参数部分并追加哈希后缀以保证唯一性。</p>
 */
public class CacheKeyGenerator {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /** 缓存 key 最大长度. */
  static final int MAX_KEY_LENGTH = 256;

  /**
   * 内部保留的 group 名称，不允许业务使用.
   *
   * <p>这些 group 名与版本对账机制的内部命名空间冲突：
   * {@code __version__} 会与版本 key 前缀碰撞导致 L2 业务 key 被误删；
   * {@code __all__} 会污染全局版本号；
   * {@code __group__:} 前缀会污染 group 版本号。</p>
   */
  private static final String[] RESERVED_GROUPS = {"__version__", "__all__"};

  /** 内部保留的 group 前缀，不允许业务 group 以此开头. */
  private static final String RESERVED_GROUP_PREFIX = "__group__:";

  /**
   * 内部保留的 cacheName，不允许业务使用.
   *
   * <p>{@code __all__} 会与全局版本 key 碰撞（incrementVersion("__all__")
   * 与 incrementAllVersion() 共用同一 Redis key）；
   * {@code __group__:} 前缀会与 group 版本 key 碰撞。</p>
   */
  private static final String[] RESERVED_CACHE_NAMES = {"__all__"};

  /** 内部保留的 cacheName 前缀. */
  private static final String RESERVED_CACHE_NAME_PREFIX = "__group__:";

  private final ExpressionParser expressionParser;

  private final ParameterNameDiscoverer parameterNameDiscoverer;

  private final ConcurrentHashMap<String, Expression> expressionCache =
      new ConcurrentHashMap<>();

  /**
   * 构造方法注入 SpEL 解析器和参数名发现器.
   *
   * @param expressionParser       SpEL 表达式解析器
   * @param parameterNameDiscoverer 方法参数名发现器，用于 {@code #参数名} 变量绑定
   */
  public CacheKeyGenerator(ExpressionParser expressionParser,
      ParameterNameDiscoverer parameterNameDiscoverer) {
    this.expressionParser = expressionParser;
    this.parameterNameDiscoverer = parameterNameDiscoverer;
  }

  /**
   * 生成缓存 key.
   *
   * <p>格式：前缀(参数部分)
   * 前缀优先级：cacheName > keyPrefix > 类全限定名.方法名(参数类型签名)。
   * 默认前缀包含参数类型签名以避免同名重载方法 key 碰撞。</p>
   *
   * <p>参数部分生成方式：</p>
   * <ul>
   *   <li>{@code keyExpression} 非空：使用 SpEL 表达式求值，结果序列化为参数部分。
   *       表达式以 {@code {}} 包裹时按 SpEL 列表字面量处理：顶层有两个及以上表达式时走多值，
   *       输出 {@code [...]}；只有一个表达式时去壳按单值处理，输出 {@code Sg[...]}。</li>
   *   <li>{@code keyExpression} 为空：使用 argIndexes 选取参数并序列化</li>
   * </ul>
   *
   * @param group         缓存分组名称，可为空；非空时作为 key 最外层前缀
   * @param method        目标方法
   * @param args          方法参数
   * @param target        目标对象（SpEL {@code #root.target} 使用）
   * @param cacheName     缓存名称，用于读写注解共享同一前缀，可为空
   * @param keyPrefix     自定义前缀，可为空
   * @param argIndexes    参与 key 生成的参数索引，为空表示全部参数
   * @param keyExpression SpEL 表达式，为空时使用 argIndexes
   * @return 缓存 key
   */
  public String generate(Method method, Object[] args, Object target,
      String group, String cacheName, String keyPrefix,
      int[] argIndexes, String keyExpression) {
    String className = method.getDeclaringClass().getName();
    String methodName = method.getName();
    String prefix;
    if (cacheName != null && !cacheName.isEmpty()) {
      validateReservedCacheName(cacheName);
      prefix = cacheName;
    } else if (keyPrefix != null && !keyPrefix.isEmpty()) {
      prefix = keyPrefix;
    } else {
      // 默认前缀包含参数类型签名，避免同类同名重载方法 key 碰撞
      // （如 findById(Long) vs findById(String)，参数序列化后字符串相同时
      // 仍能通过类型签名区分）。
      String paramTypes = Arrays.stream(method.getParameterTypes())
          .map(Class::getName)
          .collect(Collectors.joining(","));
      prefix = className + "." + methodName + "(" + paramTypes + ")";
    }

    // group 作为最外层前缀拼接，并在非 allEntries 场景校验 group 不能单独使用
    if (group != null && !group.isEmpty()) {
      validateReservedName("group", group);
      if (!StringUtils.hasText(cacheName) && !StringUtils.hasText(keyPrefix)) {
        throw new IllegalStateException(
            "@BingCache/@BingCacheEvict group='" + group + "' requires cacheName or keyPrefix. "
            + "group alone is only valid on @BingCacheEvict with allEntries=true.");
      }
      prefix = group + ":" + prefix;
    }

    String argsStr;
    if (StringUtils.hasText(keyExpression)) {
      // argSpel 按表达式形式判断单值/多值：
      // 1. 首尾空格去除后，以 "{" 开头且以 "}" 结尾
      // 2. 花括号内顶层按 "," 切分后至少有两个表达式 → 多值
      // 3. 只有一个表达式 → 单值（去掉外层花括号后求值，避免多包一层 List）
      // 切分时会跳过嵌套的 ()/[]/{} 以及字符串字面量里的逗号，与 SpEL 列表字面量语义一致
      String keyExpressionTrimmed = keyExpression.trim();
      if (isMultiValueKeyExpression(keyExpressionTrimmed)) {
        Object evaluated = evaluateKey(method, args, target, keyExpressionTrimmed);
        argsStr = serializeMultiValue(evaluated);
      } else if (isSingleBracedKeyExpression(keyExpressionTrimmed)) {
        // 单参数花括号（如 {#list}）：去掉外层花括号，按单值求值，避免 SpEL 列表字面量多包一层
        String innerExpression = keyExpressionTrimmed.substring(1,
            keyExpressionTrimmed.length() - 1).trim();
        Object evaluated = evaluateKey(method, args, target, innerExpression);
        argsStr = serializeSingle(evaluated);
      } else {
        Object evaluated = evaluateKey(method, args, target, keyExpressionTrimmed);
        argsStr = serializeSingle(evaluated);
      }
    } else {
      // argIndexes/默认:按参与参数个数决定
      Object[] keyArgs = filterArgs(args, argIndexes);
      argsStr = serializeArgs(keyArgs);
    }
    String key = prefix + "(" + argsStr + ")";
    return truncateKey(key);
  }

  /**
   * 使用 SpEL 表达式求值.
   *
   * @param method        目标方法
   * @param args          方法参数
   * @param target        目标对象
   * @param keyExpression SpEL 表达式
   * @return 求值结果
   */
  private Object evaluateKey(Method method, Object[] args, Object target,
      String keyExpression) {
    CacheExpressionRootObject rootObject =
        new CacheExpressionRootObject(method, args, target);
    MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
        rootObject, method, args, parameterNameDiscoverer);
    try {
      Expression expression = expressionCache.computeIfAbsent(keyExpression,
          expressionParser::parseExpression);
      return expression.getValue(context);
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to evaluate SpEL key expression: " + keyExpression, e);
    }
  }

  /**
   * 按顶层分隔符切分字符串，忽略嵌套括号/方括号/花括号以及字符串字面量内的分隔符.
   *
   * <p>用于解析 SpEL 列表字面量 {@code {expr1, expr2, ...}}：逗号只有在
   * 不处于任何 {@code ()}、{@code []}、{@code {}} 嵌套结构内，且不在字符串
   * 中时，才作为顶层分隔符。</p>
   *
   * @param content  要去掉外层花括号后的内容
   * @param delimiter 顶层分隔符
   * @return 切分后的顶层表达式数组
   */
  private static String[] splitTopLevel(String content, char delimiter) {
    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int depth = 0;
    boolean inString = false;
    char quoteChar = 0;
    boolean escape = false;

    for (int i = 0; i < content.length(); i++) {
      char c = content.charAt(i);
      if (inString) {
        current.append(c);
        if (escape) {
          escape = false;
        } else if (c == '\\') {
          escape = true;
        } else if (c == quoteChar) {
          inString = false;
        }
        continue;
      }
      if (c == '\'' || c == '"') {
        inString = true;
        quoteChar = c;
        current.append(c);
        continue;
      }
      if (c == '(' || c == '[' || c == '{') {
        depth++;
        current.append(c);
      } else if (c == ')' || c == ']' || c == '}') {
        depth--;
        current.append(c);
      } else if (c == delimiter && depth == 0) {
        parts.add(current.toString());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    parts.add(current.toString());
    return parts.toArray(new String[0]);
  }

  /**
   * 统计顶层表达式数组中非空片段的个数.
   */
  private static int countNonEmpty(String[] parts) {
    int count = 0;
    for (String part : parts) {
      if (!part.trim().isEmpty()) {
        count++;
      }
    }
    return count;
  }

  /**
   * 判断 SpEL 表达式是否表示多值选取.
   *
   * <p>多值约定：首尾空格去除后，以 "{" 开头且以 "}" 结尾，并且顶层按
   * "," 切分后至少包含两个非空表达式。切分时会跳过嵌套
   * {@code ()}/{@code []}/{@code {}} 以及字符串字面量里的逗号，
   * 因此 {@code {new int[]{#a, #b}, #c}} 会被正确识别为两个元素。</p>
   *
   * <p>单参数场景（如 {@code {#list}}）不满足“至少两个表达式”条件，
   * 因此按单值处理，输出 {@code Sg[...]}。</p>
   *
   * @param keyExpression 已 trim 的 SpEL 表达式
   * @return true 表示多值选取
   */
  private static boolean isMultiValueKeyExpression(String keyExpression) {
    if (keyExpression == null || keyExpression.isEmpty()) {
      return false;
    }
    if (!keyExpression.startsWith("{") || !keyExpression.endsWith("}")) {
      return false;
    }
    String content = keyExpression.substring(1, keyExpression.length() - 1);
    String trimmedContent = content.trim();
    if (trimmedContent.isEmpty()) {
      return false;
    }
    String[] parts = splitTopLevel(content, ',');
    return countNonEmpty(parts) >= 2;
  }

  /**
   * 判断 SpEL 表达式是否为单参数花括号形式（如 {@code {#list}}）.
   *
   * <p>满足以下条件的表达式按单值处理，并去掉外层花括号后直接求值：</p>
   * <ul>
   *   <li>首尾空格去除后，以 "{" 开头且以 "}" 结尾</li>
   *   <li>花括号内顶层按 "," 切分后只有一个非空表达式</li>
   * </ul>
   *
   * <p>例如 {@code {#list}} 等价于 {@code #list}，输出 {@code Sg[[...]]}；
   * 而 {@code {#a, #b}} 因顶层有两个表达式，走多值路径输出 {@code [...]}。</p>
   *
   * @param keyExpression 已 trim 的 SpEL 表达式
   * @return true 表示单参数花括号
   */
  private static boolean isSingleBracedKeyExpression(String keyExpression) {
    if (keyExpression == null || keyExpression.isEmpty()) {
      return false;
    }
    if (!keyExpression.startsWith("{") || !keyExpression.endsWith("}")) {
      return false;
    }
    String content = keyExpression.substring(1, keyExpression.length() - 1);
    String trimmedContent = content.trim();
    if (trimmedContent.isEmpty()) {
      return false;
    }
    String[] parts = splitTopLevel(content, ',');
    return countNonEmpty(parts) == 1;
  }

  /**
   * 校验 group 是否为内部保留名.
   *
   * <p>保留的 group 名称包括 {@code __version__}（与版本 key 前缀碰撞，
   * 导致 clearByGroup 误删版本 key）、{@code __all__}（污染全局版本号）、
   * 以及 {@code __group__:} 前缀（污染 group 版本号）。
   * 这些名称与版本对账机制的内部命名空间冲突，业务侧不得使用。</p>
   *
   * @param label 参数标签，用于异常消息（"group" 或 "cacheName"）
   * @param value 待校验的值
   * @throws IllegalStateException 如果值为保留名
   */
  public static void validateReservedName(String label, String value) {
    if (value == null || value.isEmpty()) {
      return;
    }
    for (String reserved : RESERVED_GROUPS) {
      if (reserved.equals(value)) {
        throw new IllegalStateException(
            label + "='" + value + "' is reserved for internal use and cannot be used "
            + "in @BingCache/@BingCacheEvict. Reserved names: __version__, __all__, "
            + "and any value starting with __group__:");
      }
    }
    if (value.startsWith(RESERVED_GROUP_PREFIX)) {
      throw new IllegalStateException(
          label + "='" + value + "' starts with reserved prefix '__group__:' and cannot be used "
          + "in @BingCache/@BingCacheEvict.");
    }
  }

  /**
   * 校验 cacheName 是否为内部保留名.
   *
   * <p>保留的 cacheName 包括 {@code __all__}（与全局版本 key 碰撞，
   * incrementVersion("__all__") 与 incrementAllVersion() 共用同一 Redis key）、
   * 以及 {@code __group__:} 前缀（与 group 版本 key 碰撞）。</p>
   *
   * @param cacheName 待校验的缓存名称
   * @throws IllegalStateException 如果值为保留名
   */
  public static void validateReservedCacheName(String cacheName) {
    if (cacheName == null || cacheName.isEmpty()) {
      return;
    }
    for (String reserved : RESERVED_CACHE_NAMES) {
      if (reserved.equals(cacheName)) {
        throw new IllegalStateException(
            "cacheName='" + cacheName + "' is reserved for internal use and cannot be used "
            + "in @BingCache/@BingCacheEvict. Reserved names: __all__, "
            + "and any value starting with __group__:");
      }
    }
    if (cacheName.startsWith(RESERVED_CACHE_NAME_PREFIX)) {
      throw new IllegalStateException(
          "cacheName='" + cacheName + "' starts with reserved prefix '__group__:' and cannot be used "
          + "in @BingCache/@BingCacheEvict.");
    }
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
   * 将参数数组序列化为字符串（argIndexes/默认方式）.
   *
   * <p>按参与 key 生成的参数个数决定输出形式：</p>
   * <ul>
   *   <li>0 个参数：返回空字符串（key 形如 {@code prefix()}）</li>
   *   <li>1 个参数：走 {@link #serializeSingle}，输出 {@code Sg[N:1]}（与 argSpel 单值一致）</li>
   *   <li>≥2 个参数：输出 {@code [N:1, N:2]}（与 argSpel {@code {#a,#b}} 多值一致）</li>
   * </ul>
   *
   * @param args 参数数组
   * @return 序列化后的字符串
   */
  private static String serializeArgs(Object[] args) {
    if (args == null || args.length == 0) {
      return "";
    }
    if (args.length == 1) {
      // 单参数：走 Sg[...] 形式，与 argSpel 单值场景产出一致
      return serializeSingle(args[0]);
    }
    // 多参数：[element1, element2, ...]，每个元素走 serializeElement（不带 Sg）
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < args.length; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(serializeElement(args[i]));
    }
    sb.append("]");
    return sb.toString();
  }

  /**
   * 序列化多值 SpEL 求值结果（argSpel 以 {@code {}} 形式）.
   *
   * <p>SpEL 列表字面量 {@code {#a, #b}} 求值后返回 {@code List}，
   * 逐元素序列化为 {@code [N:1, N:2]} 形式，与 argIndexes/默认多参数产出一致。</p>
   *
   * <p>求值结果不是 {@code List} 时（例如用户写了 {@code {#obj}} 单参数形式），
   * 统一回退为 {@link #serializeSingle}，保证被视为“一个参数”。</p>
   *
   * @param evaluated SpEL 多值求值结果（预期为 List）
   * @return 序列化后的字符串
   */
  private static String serializeMultiValue(Object evaluated) {
    if (evaluated == null) {
      return "[]";
    }
    if (evaluated instanceof List<?> list) {
      if (list.isEmpty()) {
        return "[]";
      }
      return list.stream()
          .map(CacheKeyGenerator::serializeElement)
          .collect(Collectors.joining(", ", "[", "]"));
    }
    // 非 List 的求值结果（如数组、Map 等）按单值处理：
    // 只要没有使用 {...} 多值语法，就视为一个参数，统一包装为 Sg[...]。
    return serializeSingle(evaluated);
  }

  /**
   * 单值序列化（argSpel 单值 / argIndexes 单参数 / 默认单参数）.
   *
   * <p>输出 {@code Sg[...]} 形式，其中 {@code Sg} 标识"single"（单个值选取），
   * 与多参数的 {@code [...]} 形式区分，避免单值集合与多参数碰撞。</p>
   *
   * <p>内部按类型序列化：</p>
   * <ul>
   *   <li>基本类型：{@code Sg[N:1]}、{@code Sg[S:abc]} 等</li>
   *   <li>List/数组：非空时 {@code Sg[[N:1, N:2, N:3]]}，空时 {@code Sg[]}（与多值空 {@code []} 区分）</li>
   *   <li>自定义对象：{@code Sg[{"id":1}]}</li>
   *   <li>null：{@code Sg[null]}</li>
   * </ul>
   *
   * @param arg 单个值
   * @return 序列化后的字符串
   */
  private static String serializeSingle(Object arg) {
    if (arg instanceof List<?> list && list.isEmpty()) {
      return "Sg[]";
    }
    if (arg != null && arg.getClass().isArray() && Array.getLength(arg) == 0) {
      return "Sg[]";
    }
    return "Sg[" + serializeElement(arg) + "]";
  }

  /**
   * 元素级序列化（不带 Sg 前缀）.
   *
   * <p>用于多参数的每个元素、List/数组的每个元素。
   * 对于整数数值类型使用统一数值前缀 {@code N:}，确保 {@code Integer 1} 与 {@code Long 1L}
   * 生成相同 key；字符串 {@code S:}、布尔 {@code B:}、字符 {@code C:}、小数 {@code D:}。
   * List 与数组递归走逐元素前缀拼接，输出 {@code [N:1, N:2, N:3]}。
   * 自定义对象使用 Jackson 序列化为 JSON。</p>
   *
   * @param arg 元素值
   * @return 序列化后的字符串
   */
  private static String serializeElement(Object arg) {
    if (arg == null) {
      return "null";
    }
    if (arg instanceof Byte || arg instanceof Short || arg instanceof Integer
        || arg instanceof Long || arg instanceof BigInteger) {
      return "N:" + arg;
    }
    if (arg instanceof String) {
      return "S:" + arg;
    }
    if (arg instanceof Boolean) {
      return "B:" + arg;
    }
    if (arg instanceof Character) {
      return "C:" + arg;
    }
    if (arg instanceof Number) {
      return "D:" + arg;
    }
    if (arg instanceof List<?>) {
      @SuppressWarnings("unchecked")
      List<Object> list = (List<Object>) arg;
      if (list.isEmpty()) {
        return "[]";
      }
      return list.stream()
          .map(CacheKeyGenerator::serializeElement)
          .collect(Collectors.joining(", ", "[", "]"));
    }
    if (arg.getClass().isArray()) {
      return serializeArray(arg);
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
   * 递归序列化数组元素，避免数组内部的字符串值和数值发生 key 碰撞.
   *
   * <p>输出形式为 {@code [N:1, N:2, N:3]}，与 List 的序列化形式一致
   *（业务语义等价，数组和 List 在 key 中不区分）。</p>
   *
   * @param array 数组参数，可为对象数组或基本类型数组
   * @return 序列化后的数组字符串
   */
  private static String serializeArray(Object array) {
    int length = Array.getLength(array);
    if (length == 0) {
      return "[]";
    }
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < length; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(serializeElement(Array.get(array, i)));
    }
    sb.append("]");
    return sb.toString();
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

  /**
   * SpEL 表达式求值的 Root 对象.
   *
   * <p>通过 {@code #root} 访问，提供方法元信息和目标对象：
   * {@code #root.method}、{@code #root.args}、{@code #root.target}、{@code #root.methodName}。</p>
   */
  static final class CacheExpressionRootObject {

    private final Method method;

    private final Object[] args;

    private final Object target;

    CacheExpressionRootObject(Method method, Object[] args, Object target) {
      this.method = method;
      this.args = args;
      this.target = target;
    }

    public Method getMethod() {
      return method;
    }

    public String getMethodName() {
      return method.getName();
    }

    public Object[] getArgs() {
      return args;
    }

    public Object getTarget() {
      return target;
    }
  }
}
