# Bing Cache 模块实现进度

> 更新日期: 2026-06-15
> 最新提交: 7bdea680a Merge "[bing-cache]L1+L2二级缓存 + @BingCacheEvict + 代码优化" into dev_hdjt
> Jira: #BCOCM-1312

## 已完成功能

### 1. 核心注解 @BingCache

位置: `com.bing.cache.annotation.BingCache`

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| cacheName | String | "" | 缓存名称，用于与 @BingCacheEvict 共享同一前缀（优先级最高） |
| keyPrefix | String | "" | 缓存 key 前缀，为空时使用"类名.方法名"；cacheName 不为空时忽略 |
| expireTime | int | 0 | 过期时间（秒），0 表示不过期 |
| argIndexes | int[] | {} | 参与 key 生成的参数索引，空数组表示全部参数 |
| cacheNullValue | boolean | false | 是否缓存 null 结果，true 时防止缓存穿透 |

### 2. 缓存清除注解 @BingCacheEvict

位置: `com.bing.cache.annotation.BingCacheEvict`

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| cacheName | String | "" | 缓存名称，需与 @BingCache 的 cacheName 一致才能匹配 |
| keyPrefix | String | "" | 缓存 key 前缀，同 @BingCache；cacheName 不为空时忽略 |
| argIndexes | int[] | {} | 参与 key 生成的参数索引，需与 @BingCache 对应 |
| allEntries | boolean | false | true 时清空所有缓存（调用 cacheManager.clear()），忽略 key |
| beforeInvocation | boolean | false | true 时在方法执行前清除缓存；默认方法成功后才清除 |

**注意**：`allEntries = true` 时，有 cacheName/keyPrefix 则只清除该前缀下的缓存；都没有时才全局清空。

### 3. 缓存管理器

- `CacheManager` 接口 — 定义 get/put/evict/clear 方法
- `CaffeineCacheManager` 实现 — 基于 Caffeine，按过期时间分组管理缓存实例
  - maxSize 可配置（构造器参数，默认 1000）
  - `get()` 遍历所有缓存实例查找，支持不同过期时间的缓存命中
- `RedisCacheManager` 实现 — 基于 Redis 的 L2 分布式缓存
  - 使用 GenericJackson2JsonRedisSerializer 序列化（带 @class 类型信息）
  - key 前缀命名空间隔离（默认 `bing-cache:`）
  - `clear()` 使用 SCAN 避免阻塞 Redis
  - `getRemainingTtl()` 获取 Redis key 剩余过期时间，用于 L1 回填
  - 连续失败 3 次输出 WARN 降级日志，恢复正常输出 INFO 恢复日志
- `CompositeCacheManager` 实现 — L1(Caffeine) + L2(Redis) 组合缓存
  - 读取: L1 命中→返回 / L1 未命中→L2 命中→回填 L1(携带剩余 TTL)→返回 / 都未命中→null
  - 写入: 同时写 L1 和 L2
  - 失效: 清 L1 + 清 L2 + 发布 Pub/Sub 通知其他实例清 L1

### 4. 缓存失效通知 (Pub/Sub)

- `CacheInvalidationPublisher` 接口 — 发布失效通知
- `RedisCacheInvalidationPublisher` — 基于 Redis Pub/Sub 实现，携带 instanceId
- `CacheInvalidationMessage` — 消息体 (EVICT/CLEAR + key + timestamp + instanceId)
- `CacheInvalidationListener` — 接收通知并清理本地 L1 缓存，过滤自身发出的消息（自发自滤）
- 频道: `bing-cache:invalidation`（可配置）

### 5. AOP 切面

- `CacheAspect` — @Around 环绕通知，拦截 @BingCache 注解方法
  - 先查缓存，命中直接返回；未命中执行方法并缓存结果
  - 返回 null 时根据 cacheNullValue 决定是否缓存（使用 NullValue.INSTANCE 占位符）
- `CacheEvictAspect` — @Around 环绕通知，拦截 @BingCacheEvict 注解方法
  - 支持 beforeInvocation（方法前清除）和 allEntries（清空所有缓存）
  - 默认行为：方法成功执行后才清除缓存，方法抛异常则不清除

### 6. 自动配置

- `BingCacheAutoConfiguration` — 条件装配：
  - Caffeine 在 classpath + 无 Redis 连接 → 仅 L1 本地缓存
  - Caffeine + Redis 连接可用 + `bing.cache.redis.enabled=true` → L1+L2 二级缓存
  - `@ConditionalOnBean(RedisConnectionFactory.class)` 确保只有配置了 Redis 才激活 L2
- 内部 `RedisConfiguration` — 注册专用 RedisTemplate、Pub/Sub 监听器
- `spring.factories` + `AutoConfiguration.imports` — Spring Boot 2.x/3.x 双格式注册
- `bingCacheInstanceId` Bean — UUID 实例标识，用于 Pub/Sub 自发自滤

### 7. 配置属性

`BingCacheProperties` (`@ConfigurationProperties(prefix="bing.cache")`)

```yaml
bing:
  cache:
    caffeine:
      max-size: 1000          # 每个 Caffeine Cache 实例的最大条目数（按过期时间分组，实际全局上限为 N×maxSize）
    redis:
      enabled: true           # 是否启用 L2 Redis 缓存
      key-prefix: "bing-cache:"         # Redis key 前缀
      channel-name: "bing-cache:invalidation"  # Pub/Sub 频道
```

### 8. 工具类

- `CacheKeyGenerator` — 生成规则: 前缀(参数1,参数2,...)
  - 前缀优先级: cacheName > keyPrefix > 类全限定名.方法名
  - 参数序列化: 基本类型用 String.valueOf，数组用 deepToString，自定义对象用 Jackson JSON
  - Key 长度限制 256 字符，超长截断并追加哈希后缀 `...#hexHash`

### 9. 单元测试 (98 个，全部通过)

| 测试类 | 用例数 | 覆盖范围 |
|--------|--------|----------|
| CacheKeyGeneratorTest | 14 | key 生成逻辑、cacheName 优先级、Jackson 序列化、key 截断 |
| CaffeineCacheManagerTest | 12 | 缓存存取、过期、清除、maxSize 构造器、按前缀清除 |
| CacheAspectTest | 6 | AOP 缓存拦截、null 处理、cacheNullValue |
| CacheEvictAspectTest | 9 | 缓存清除、allEntries、beforeInvocation、按cacheName清除、异常处理 |
| BingCacheAutoConfigurationTest | 2 | 自动配置 |
| CacheInvalidationMessageTest | 7 | 消息序列化/反序列化、CLEAR_PREFIX 类型 |
| RedisCacheInvalidationPublisherTest | 7 | Pub/Sub 发布、异常处理、clearByPrefix |
| CacheInvalidationListenerTest | 7 | 消息监听、自发自滤、L1 失效、CLEAR_PREFIX |
| RedisCacheManagerTest | 18 | Redis 缓存存取、TTL、降级/恢复、clearByPrefix、异常处理 |
| CompositeCacheManagerTest | 9 | L1/L2 组合读写、回填、失效、按前缀清除 |
| BingCachePropertiesTest | 3 | 配置默认值和自定义值 |

## 已修复问题

### 2026-06-05

- **Bug #1**: `CaffeineCacheManager.get()` 只查默认缓存（expireSeconds=0），导致带过期时间的缓存永远命中不了。修复为遍历所有缓存实例查找。
- **Bug #2**: 方法返回 null 时 `cache.put(key, null)` 抛 NPE（Caffeine 不支持 null 值）。修复为 null 结果不缓存。

### 2026-06-12

- **L1 回填 TTL 问题**: L2 回填 L1 时 expireSeconds=0 导致 L1 永不过期，即使 L2 已过期。修复为通过 `RedisCacheManager.getRemainingTtl()` 获取 L2 剩余 TTL 回填 L1。
- **@BingCacheEvict 默认前缀不匹配**: 默认前缀用方法名，导致 evict 的 key 和 cache 的 key 不一致。新增 `cacheName` 属性作为读写注解配对标识。
- **Lombok 移除后 key 生成问题**: 移除 Lombok 后自定义对象无 toString()，key 生成不确定。修复为使用 Jackson ObjectMapper 序列化。
- **Redis 降级静默**: Redis 不可用时只有 ERROR 日志，无降级提示。新增连续失败计数器，3 次失败输出 WARN 降级日志，恢复输出 INFO 日志。
- **移除 Lombok 依赖**: 所有类手写 getter/setter/Logger，checkstyle 字段命名 `LOG`/`REDIS_LOG`。

## 技术栈

- Java 21
- Spring Boot 3.5.13 (BOM 管理版本)
- Caffeine (由 Spring Boot BOM 管理版本)
- Spring Data Redis (provided scope，使用者提供)
- AspectJ (由 Spring Boot BOM 管理版本)
- Jackson (key 生成 + Redis 序列化)
- JUnit 5 + Mockito (spring-boot-starter-test)
- Testcontainers (集成测试，GenericContainer + redis:7-alpine)

### 构建注意事项

- **必须用 JDK 21 运行 Maven**: `JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"`
- 系统默认 JAVA_HOME 是 Dragonwell JDK 8，会导致 Caffeine 3.x 类加载失败

## 进行中

### 集成测试 (待验证)

- `CompositeCacheManagerIntegrationTest` — 使用 Testcontainers (GenericContainer + redis:7-alpine)
  - 验证 L1/L2 读写、L2 回填 L1、evict/clear 双层失效
  - 验证 Pub/Sub 跨实例失效通知
  - 验证 Redis TTL
  - **需要 Docker 环境运行**

## 待改进功能

### P0 - 高优先级（全部完成 ✅）

- [x] **Redis 扩展** — RedisCacheManager + CompositeCacheManager，支持分布式缓存
- [x] **@BingCacheEvict** — 缓存清除注解，支持主动清除（allEntries + beforeInvocation）
- [x] **cacheNullValue** — 配置是否缓存 null 结果（默认不缓存，防止缓存穿透）
- [x] **Redis 降级** — Redis 连续失败 3 次输出 WARN 降级日志，恢复正常输出 INFO 恢复日志
- [x] **Jackson Key 序列化** — 自定义对象使用 Jackson 序列化生成 key，不依赖 toString()
- [x] **Key 长度限制** — 超长 key 截断到 256 字符并追加哈希后缀保证唯一性
- [x] **L1 回填携带 L2 剩余 TTL** — 通过 Redis TTL 命令获取剩余过期时间回填 L1
- [x] **Pub/Sub 自发自滤** — 消息携带 instanceId，Listener 过滤自身发出的消息
- [x] **移除 Lombok** — 所有类手写 getter/setter/Logger
- [x] **README 文档** — 详细的注解使用说明、架构图、配置属性、注意事项

### P1 - 中优先级

- [x] **按 cacheName 分组清除** — `allEntries = true` 时按 cacheName/keyPrefix 前缀匹配清除，而非全局清空。CacheManager 新增 `clearByPrefix()`，Pub/Sub 新增 `CLEAR_PREFIX` 消息类型。
- [ ] **SpEL 表达式** — keyExpression 属性支持动态 key 生成
- [ ] **maxSize** — 按方法配置最大缓存条数

### P2 - 低优先级

- [ ] **缓存统计** — 命中率、缓存大小等监控指标
- [ ] **@BingCachePut** — 强制更新缓存注解
- [ ] **缓存事件** — 支持缓存命中/失效事件监听

## 关键设计决策

1. **CacheManager 接口不变** — CacheAspect 只依赖接口，新增实现无需修改切面
2. **@ConditionalOnBean(RedisConnectionFactory.class)** — 只有配置了 Redis 连接才激活 L2，否则自动降级为纯 L1
3. **L2 回填 L1 时携带剩余 TTL** — 通过 `RedisCacheManager.getRemainingTtl()` 获取 L2 剩余过期时间，回填 L1 时使用该值，避免 L1 回填条目在 L2 过期后长期驻留
4. **Pub/Sub fire-and-forget** — Redis Pub/Sub 不保证送达，`RedisMessageListenerContainer` 自动重连。MVP 可接受
5. **GenericJackson2JsonRedisSerializer** — 带 @class 类型信息，确保反序列化类型正确，要求同构服务部署
6. **cacheName 优先级高于 keyPrefix** — cacheName 是读写注解的配对标识，语义更明确；keyPrefix 只是替换默认前缀
7. **allEntries = true 按前缀清除** — `@BingCacheEvict(allEntries=true)` 配合 cacheName/keyPrefix 时只清除该前缀下的缓存；都不指定时才全局清空
8. **NullValue 占位符** — Caffeine 不支持 null 值，使用内部 NullValue.INSTANCE 占位符存储，读取时自动还原为 null

## Git 提交规范

提交时使用以下模板（Change-Id 由 Gerrit hook 自动生成）：

```
[bing-cache] 提交主题

Code Source From: Self Code
AI Co-author: NONE
Description: 详细描述
Jira: #BCOCM-XXXX
市场项目编号（名称）：
```

**注意事项：**
- 主题格式：`[bing-cache]` + 简短描述
- Change-Id 不要手动添加，由 `.git/hooks/commit-msg` 自动生成
- Jira 单号每次提交时需要提供
- 提交前必须运行 `mvn validate` 确保 checkstyle 通过
- 只关注 bing-cache 工程本身的变更，不提交其他模块的文件
