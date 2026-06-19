package com.bing.cache.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bing Cache 配置属性.
 *
 * <p>通过 {@code bing.cache} 前缀绑定配置，
 * 支持 Caffeine 本地缓存和 Redis 分布式缓存的配置。</p>
 *
 * <p>使用示例：</p>
 * <pre>
 * bing:
 *   cache:
 *     caffeine:
 *       max-size: 2000
 *       l1-max-ttl: 300
 *     redis:
 *       enabled: true
 *       key-prefix: "myapp:cache:"
 *       channel-name: "myapp:cache:invalidation"
 *     reconciliation:
 *       enabled: true
 *       interval: 30
 * </pre>
 */
@ConfigurationProperties(prefix = "bing.cache")
@Validated
public class BingCacheProperties {

  /**
   * Caffeine 本地缓存配置.
   */
  @Valid
  private Caffeine caffeine = new Caffeine();

  /**
   * Redis 分布式缓存配置.
   */
  @Valid
  private Redis redis = new Redis();

  /**
   * 版本对账配置.
   */
  @Valid
  private Reconciliation reconciliation = new Reconciliation();

  public Caffeine getCaffeine() {
    return caffeine;
  }

  public void setCaffeine(Caffeine caffeine) {
    this.caffeine = caffeine;
  }

  public Redis getRedis() {
    return redis;
  }

  public void setRedis(Redis redis) {
    this.redis = redis;
  }

  public Reconciliation getReconciliation() {
    return reconciliation;
  }

  public void setReconciliation(Reconciliation reconciliation) {
    this.reconciliation = reconciliation;
  }

  /**
   * Caffeine 缓存配置.
   */
  public static class Caffeine {

    /**
     * Caffeine Cache 的最大条目数.
     */
    @Min(1)
    private long maxSize = 1000L;

    /**
     * L1 最大存活秒数，0 表示不限制.
     *
     * <p>当配置为正数时，所有 L1 条目的过期时间不超过该值，
     * 作为 Pub/Sub 丢失或 Redis 不可用时的兜底保障。</p>
     *
     * <p><b>默认兜底行为：</b>属性默认值为 0。在 L1+L2 二级缓存模式下，
     * 若保持 0，{@link com.bing.cache.config.BingCacheAutoConfiguration} 会自动使用
     * {@code DEFAULT_L1_MAX_TTL_SECONDS}（300 秒）作为实际生效值，并输出 INFO 日志说明。
     * 原因：L1+L2 模式下单 key evict 的 Pub/Sub 丢失无法通过对账补偿，需要 l1-max-ttl 兜底，
     * 否则脏数据会在 L1 中无限期驻留。纯 L1 模式下 0 即不限制，不自动兜底。</p>
     *
     * <p>如需禁用此兜底（不推荐），可在 L1+L2 模式下显式设置一个足够大的正数。</p>
     */
    @Min(0)
    private long l1MaxTtl = 0L;

    public long getMaxSize() {
      return maxSize;
    }

    public void setMaxSize(long maxSize) {
      this.maxSize = maxSize;
    }

    public long getL1MaxTtl() {
      return l1MaxTtl;
    }

    public void setL1MaxTtl(long l1MaxTtl) {
      this.l1MaxTtl = l1MaxTtl;
    }
  }

  /**
   * Redis 缓存配置.
   */
  public static class Redis {

    /**
     * 是否启用 L2 Redis 缓存.
     *
     * <p>仅在 classpath 中存在 Redis 依赖时生效。</p>
     */
    private boolean enabled = true;

    /**
     * Redis key 前缀.
     */
    private String keyPrefix = "bing-cache:";

    /**
     * 缓存失效通知 Pub/Sub 频道名称.
     */
    private String channelName = "bing-cache:invalidation";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getKeyPrefix() {
      return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
      this.keyPrefix = keyPrefix;
    }

    public String getChannelName() {
      return channelName;
    }

    public void setChannelName(String channelName) {
      this.channelName = channelName;
    }
  }

  /**
   * 版本对账配置.
   */
  public static class Reconciliation {

    /**
     * 是否启用版本对账.
     *
     * <p>启用后，定时检查 Redis 中的版本号变化，
     * 发现变化时清除对应前缀的 L1 缓存，
     * 作为 Redis Pub/Sub 消息丢失的补偿机制。</p>
     */
    private boolean enabled = true;

    /**
     * 对账间隔秒数.
     *
     * <p>必须为正整数。设为 0 或负数会导致 {@code scheduleAtFixedRate} 抛出
     * {@link IllegalArgumentException}，启动失败。</p>
     */
    @Min(1)
    private long interval = 30L;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public long getInterval() {
      return interval;
    }

    public void setInterval(long interval) {
      this.interval = interval;
    }
  }
}
