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

package com.bing.cache.config;

import com.bing.cache.aspect.CacheAspect;
import com.bing.cache.aspect.CacheEvictAspect;
import com.bing.cache.cache.CacheInvalidationListener;
import com.bing.cache.cache.CacheInvalidationPublisher;
import com.bing.cache.cache.CacheManager;
import com.bing.cache.cache.CacheReconciliationService;
import com.bing.cache.cache.CacheVersionStore;
import com.bing.cache.cache.CaffeineCacheManager;
import com.bing.cache.cache.CompositeCacheManager;
import com.bing.cache.cache.RedisCacheInvalidationPublisher;
import com.bing.cache.cache.RedisCacheManager;
import com.bing.cache.util.CacheKeyGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.util.UUID;

/**
 * Bing Cache 自动配置类.
 *
 * <p>根据 classpath 和配置条件自动装配缓存组件：</p>
 * <ul>
 *   <li>Caffeine 在 classpath 且 Redis 不可用时：仅 L1 本地缓存</li>
 *   <li>Caffeine + Redis 在 classpath 且 {@code bing.cache.redis.enabled=true} 时：L1+L2 二级缓存</li>
 * </ul>
 *
 * <p>用户可通过自定义 {@link CacheManager} Bean 覆盖默认实现。</p>
 */
@Configuration
@EnableConfigurationProperties(BingCacheProperties.class)
@AutoConfigureAfter(RedisAutoConfiguration.class)
public class BingCacheAutoConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(BingCacheAutoConfiguration.class);

  /**
   * L1+L2 模式下 l1-max-ttl 的默认兜底值（秒）.
   *
   * <p>当用户未显式配置 {@code bing.cache.caffeine.l1-max-ttl}（或设为 0）时，
   * 在 L1+L2 二级缓存模式下使用此值作为 L1 最大存活时间。
   * 纯 L1 模式不受影响，仍按用户配置（默认 0=不限制）。</p>
   *
   * <p>原因：L1+L2 模式下若 l1-max-ttl=0（不限制），单 key evict 的 Pub/Sub 丢失
   * 无法通过对账补偿，脏数据会在 L1 中无限期驻留。300 秒作为兜底，
   * 在缓存命中率和一致性之间取平衡。</p>
   */
  static final long DEFAULT_L1_MAX_TTL_SECONDS = 300L;

  /**
   * 注册当前实例的唯一标识，用于 Pub/Sub 消息的自发自滤.
   *
   * <p>Publisher 发送消息时携带此 ID，Listener 收到后过滤掉自己发出的消息，
   * 避免本地缓存被重复清除。</p>
   *
   * @return 实例唯一标识
   */
  @Bean("bingCacheInstanceId")
  public String bingCacheInstanceId() {
    return UUID.randomUUID().toString();
  }

  /**
   * 注册缓存版本号存储（仅 Redis 可用且对账启用时）.
   *
   * <p>作为单一 bean 供 {@link CompositeCacheManager} 和 {@link CacheReconciliationService}
   * 共享，避免此前两处各自 new 一个实例的冗余。</p>
   *
   * @param stringRedisTemplate Redis 字符串操作模板
   * @param properties          缓存配置属性
   * @return CacheVersionStore 实例
   */
  @Bean
  @ConditionalOnBean(RedisConnectionFactory.class)
  @ConditionalOnProperty(prefix = "bing.cache.reconciliation", name = "enabled",
      havingValue = "true", matchIfMissing = true)
  @ConditionalOnMissingBean
  public CacheVersionStore cacheVersionStore(
      StringRedisTemplate stringRedisTemplate,
      BingCacheProperties properties) {
    return new CacheVersionStore(stringRedisTemplate,
        properties.getRedis().getKeyPrefix() + "version:");
  }

  /**
   * 注册 L1+L2 组合缓存管理器（Redis 连接可用且启用时优先）.
   *
   * <p>当 classpath 中存在 Redis 连接且 {@code bing.cache.redis.enabled=true}（默认）时，
   * 优先使用 L1(Caffeine) + L2(Redis) 二级缓存模式。</p>
   *
   * @param stringRedisTemplate    Redis 字符串操作模板
   * @param bingCacheRedisTemplate Redis 对象操作模板
   * @param properties             缓存配置属性
   * @param bingCacheInstanceId    实例唯一标识
   * @param versionStoreProvider   版本号存储 ObjectProvider（对账禁用时为空）
   * @return CompositeCacheManager 实例
   */
  @Bean("cacheManager")
  @ConditionalOnMissingBean(CacheManager.class)
  @ConditionalOnBean(RedisConnectionFactory.class)
  @ConditionalOnProperty(prefix = "bing.cache.redis", name = "enabled",
      havingValue = "true", matchIfMissing = true)
  public CacheManager compositeCacheManager(
      StringRedisTemplate stringRedisTemplate,
      RedisTemplate<String, Object> bingCacheRedisTemplate,
      BingCacheProperties properties,
      String bingCacheInstanceId,
      ObjectProvider<CacheVersionStore> versionStoreProvider) {
    // L1+L2 模式下，若用户未显式设置 l1-max-ttl（<=0），使用默认兜底值。
    // 纯 L1 模式不进入此分支，不受影响。
    long configuredL1MaxTtl = properties.getCaffeine().getL1MaxTtl();
    long effectiveL1MaxTtl = configuredL1MaxTtl > 0
        ? configuredL1MaxTtl
        : DEFAULT_L1_MAX_TTL_SECONDS;
    boolean usingDefaultL1MaxTtl = configuredL1MaxTtl <= 0;

    CaffeineCacheManager l1CacheManager = new CaffeineCacheManager(
        properties.getCaffeine().getMaxSize(),
        effectiveL1MaxTtl);
    RedisCacheManager l2CacheManager = new RedisCacheManager(
        bingCacheRedisTemplate,
        properties.getRedis().getKeyPrefix(),
        properties.getRedis().getScanCount(),
        properties.getRedis().getDeleteBatchSize(),
        properties.getRedis().isUseUnlink(),
        properties.getRedis().getFailureLogInterval());
    CacheInvalidationPublisher publisher = new RedisCacheInvalidationPublisher(
        stringRedisTemplate, properties.getRedis().getChannelName(), bingCacheInstanceId);

    // 版本对账（默认启用）：通过 ObjectProvider 获取共享 bean，对账禁用时为 null
    CacheVersionStore versionStore = versionStoreProvider.getIfAvailable();

    CompositeCacheManager composite = new CompositeCacheManager(
        l1CacheManager, l2CacheManager, publisher, versionStore);

    // Redis 恢复策略：
    // - 对账已启用：不立即全量清空 L1，由对账服务在下一个周期（intervalSeconds 内）
    //   按 cacheName 粒度分批清理，避免瞬间全量驱逐引发回源冲击（stampede）
    // - 对账未启用：立即全量清空 L1，防止 Redis 恢复后脏数据持续暴露
    if (properties.getReconciliation().isEnabled()) {
      l2CacheManager.setRecoveryCallback(() ->
          LOG.info("Bing Cache: Redis recovered; L1 stale entries will be cleared"
              + " at next reconciliation cycle (within {}s)",
              properties.getReconciliation().getInterval()));
    } else {
      l2CacheManager.setRecoveryCallback(l1CacheManager::clear);
    }

    LOG.info("Bing Cache: L1 (Caffeine) + L2 (Redis) composite mode enabled"
        + ", reconciliation={}, l1MaxTtl={}s{}",
        properties.getReconciliation().isEnabled(),
        effectiveL1MaxTtl,
        usingDefaultL1MaxTtl
            ? " (default; configure bing.cache.caffeine.l1-max-ttl to override)"
            : "");

    return composite;
  }

  /**
   * 注册纯 Caffeine 缓存管理器（Redis 不可用或禁用时的回退方案）.
   *
   * <p>当 Redis 连接不可用或 {@code bing.cache.redis.enabled=false} 时，
   * 通过 {@code @ConditionalOnMissingBean} 回退到纯 L1 本地缓存模式。</p>
   *
   * @param properties 缓存配置属性
   * @return CacheManager 实例
   */
  @Bean("cacheManager")
  @ConditionalOnMissingBean(CacheManager.class)
  public CacheManager caffeineCacheManager(BingCacheProperties properties) {
    LOG.info("Bing Cache: L1 (Caffeine) only mode, l1MaxTtl={}s",
        properties.getCaffeine().getL1MaxTtl());
    return new CaffeineCacheManager(
        properties.getCaffeine().getMaxSize(),
        properties.getCaffeine().getL1MaxTtl());
  }

  /**
   * 注册缓存 key 生成器.
   *
   * <p>内置 SpEL 解析器和参数名发现器，支持通过 {@code argSpel} SpEL 表达式选取参数。
   * 用户可通过自定义 {@link CacheKeyGenerator} Bean 覆盖（如需替换 SpEL 解析器或参数名发现器）。</p>
   *
   * @param parameterNameDiscovererProvider 方法参数名发现器（可选注入，未提供时使用 DefaultParameterNameDiscoverer）
   * @return CacheKeyGenerator 实例
   */
  @Bean
  @ConditionalOnMissingBean
  public CacheKeyGenerator cacheKeyGenerator(
      ObjectProvider<ParameterNameDiscoverer> parameterNameDiscovererProvider) {
    ParameterNameDiscoverer discoverer = parameterNameDiscovererProvider.getIfAvailable(
        DefaultParameterNameDiscoverer::new);
    return new CacheKeyGenerator(new SpelExpressionParser(), discoverer);
  }

  /**
   * 注册缓存切面.
   *
   * @param cacheManager      缓存管理器
   * @param cacheKeyGenerator 缓存 key 生成器
   * @return CacheAspect 实例
   */
  @Bean
  @ConditionalOnMissingBean
  public CacheAspect cacheAspect(CacheManager cacheManager, CacheKeyGenerator cacheKeyGenerator) {
    return new CacheAspect(cacheManager, cacheKeyGenerator);
  }

  /**
   * 注册缓存清除切面.
   *
   * @param cacheManager      缓存管理器
   * @param cacheKeyGenerator 缓存 key 生成器
   * @return CacheEvictAspect 实例
   */
  @Bean
  @ConditionalOnMissingBean
  public CacheEvictAspect cacheEvictAspect(CacheManager cacheManager,
      CacheKeyGenerator cacheKeyGenerator) {
    return new CacheEvictAspect(cacheManager, cacheKeyGenerator);
  }

  /**
   * Redis 相关 Bean 配置.
   *
   * <p>仅在 Redis 连接可用且启用时激活，注册专用 RedisTemplate、
   * Pub/Sub 监听器、版本对账服务等组件。</p>
   */
  @Configuration
  @ConditionalOnBean(RedisConnectionFactory.class)
  @ConditionalOnProperty(prefix = "bing.cache.redis", name = "enabled",
      havingValue = "true", matchIfMissing = true)
  static class RedisConfiguration {

    private static final Logger REDIS_LOG = LoggerFactory.getLogger(RedisConfiguration.class);

    /**
     * 注册 Bing Cache 专用的 RedisTemplate.
     *
     * <p>key 使用 StringRedisSerializer，value 使用 GenericJackson2JsonRedisSerializer
     * （带 @class 类型信息，确保反序列化时类型正确）。</p>
     *
     * @param connectionFactory Redis 连接工厂
     * @return RedisTemplate 实例
     */
    @Bean("bingCacheRedisTemplate")
    @ConditionalOnMissingBean(name = "bingCacheRedisTemplate")
    public RedisTemplate<String, Object> bingCacheRedisTemplate(
        RedisConnectionFactory connectionFactory) {
      RedisTemplate<String, Object> template = new RedisTemplate<>();
      template.setConnectionFactory(connectionFactory);
      template.setKeySerializer(new StringRedisSerializer());
      template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
      template.setHashKeySerializer(new StringRedisSerializer());
      template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
      template.afterPropertiesSet();
      return template;
    }

    /**
     * 注册缓存失效消息监听器.
     *
     * <p>监听器需要 L1 缓存管理器实例；通过 CompositeCacheManager 暴露的 L1 访问器获取。</p>
     *
     * @param cacheManager 缓存管理器（CompositeCacheManager 实例）
     * @return CacheInvalidationListener 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public CacheInvalidationListener cacheInvalidationListener(CacheManager cacheManager,
        String bingCacheInstanceId) {
      CacheManager l1;
      if (cacheManager instanceof CompositeCacheManager composite) {
        l1 = composite.getL1CacheManager();
      } else {
        // 非 Composite 模式下不应创建此 bean，但作为防御性处理
        l1 = cacheManager;
      }
      return new CacheInvalidationListener(l1, bingCacheInstanceId);
    }

    /**
     * 注册 Redis Pub/Sub 消息监听适配器.
     *
     * <p>显式调用 {@link MessageListenerAdapter#afterPropertiesSet()} 初始化内部
     * MethodInvoker，避免容器收到消息时因 invoker 未初始化而抛出 NPE。</p>
     *
     * @param listener 缓存失效监听器
     * @return 已初始化的 MessageListenerAdapter
     */
    @Bean("bingCacheInvalidationMessageListenerAdapter")
    @ConditionalOnMissingBean(name = "bingCacheInvalidationMessageListenerAdapter")
    public MessageListenerAdapter cacheInvalidationMessageListenerAdapter(
        CacheInvalidationListener listener) {
      MessageListenerAdapter adapter = new MessageListenerAdapter(listener, "handleMessage");
      adapter.afterPropertiesSet();
      return adapter;
    }

    /**
     * 注册 Redis Pub/Sub 消息监听容器.
     *
     * <p>订阅缓存失效频道，收到消息后调用
     * {@link CacheInvalidationListener#handleMessage(String)} 处理。</p>
     *
     * @param connectionFactory Redis 连接工厂
     * @param properties        缓存配置属性
     * @param adapter           缓存失效消息监听适配器
     * @return RedisMessageListenerContainer 实例
     */
    @Bean("bingCacheInvalidationListenerContainer")
    @ConditionalOnMissingBean(name = "bingCacheInvalidationListenerContainer")
    public RedisMessageListenerContainer bingCacheInvalidationListenerContainer(
        RedisConnectionFactory connectionFactory,
        BingCacheProperties properties,
        @Qualifier("bingCacheInvalidationMessageListenerAdapter") MessageListenerAdapter adapter) {
      RedisMessageListenerContainer container = new RedisMessageListenerContainer();
      container.setConnectionFactory(connectionFactory);
      container.addMessageListener(adapter,
          new PatternTopic(properties.getRedis().getChannelName()));
      REDIS_LOG.info("Bing Cache: Redis Pub/Sub listener registered on channel: {}",
          properties.getRedis().getChannelName());
      return container;
    }

    /**
     * 注册版本对账服务.
     *
     * <p>仅在 Redis 可用且对账启用时注册。
     * 服务随 Spring 容器生命周期自动启停。</p>
     *
     * @param versionStore  版本号存储（共享 bean）
     * @param cacheManager  缓存管理器
     * @param properties    缓存配置属性
     * @return CacheReconciliationService 实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "bing.cache.reconciliation", name = "enabled",
        havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public CacheReconciliationService cacheReconciliationService(
        CacheVersionStore versionStore,
        CacheManager cacheManager,
        BingCacheProperties properties) {
      CacheManager l1;
      if (cacheManager instanceof CompositeCacheManager composite) {
        l1 = composite.getL1CacheManager();
      } else {
        l1 = cacheManager;
      }
      CacheReconciliationService service = new CacheReconciliationService(
          versionStore, l1, properties.getReconciliation().getInterval());
      REDIS_LOG.info("Bing Cache: Reconciliation service configured, interval={}s",
          properties.getReconciliation().getInterval());
      return service;
    }
  }
}
