# bing-cache-test

BingCache 的集成测试与功能演示工程。本模块是一个完整可运行的 Spring Boot 应用，依赖 Maven reactor 中的 `bing-cache-core` 构建产物，通过真实的 L1+L2 缓存环境验证各项功能。

> **注意**：本模块为测试/演示用途，不是生产代码。

---

## 前置条件

| 依赖 | 要求 |
|------|------|
| JDK | 17+（推荐 21） |
| Maven | 3.8+（仓库无 mvn wrapper，使用系统 `mvn`） |
| Redis | `cmac-mini:6379`，密码 `RedRain123` |

> 所有命令须从**父仓库根目录**执行，使用 `-pl bing-cache-test -am` 以确保 Maven 先构建 `bing-cache-core`。

---

## 快速开始

### 运行单实例集成测试

```bash
# 运行本模块全部测试
mvn -pl bing-cache-test -am test

# 只运行 BingCacheTest（功能回归）
mvn -pl bing-cache-test -am -Dtest=BingCacheTest test

# 运行单个测试方法
mvn -pl bing-cache-test -am -Dtest=BingCacheTest#testBasicCache test
```

### 启动 Demo 应用（HTTP 手动验证）

```bash
mvn -pl bing-cache-test -am spring-boot:run
```

应用启动后监听 `http://localhost:8080`，可通过下方 HTTP 接口进行手动验证。

### 多实例测试

参见 [MULTI_INSTANCE_TEST.md](MULTI_INSTANCE_TEST.md)。

---

## 测试覆盖

### BingCacheTest（自动化回归，`src/test/`，25 个用例）

| 测试方法 | 验证要点 |
|----------|----------|
| `testBasicCache` | 基础缓存命中，第二次调用耗时显著缩短 |
| `testKeyPrefix` | 自定义 `keyPrefix` 生效 |
| `testCacheNullValue` | `cacheNullValue=true`：null 被缓存，防止穿透 |
| `testNoCacheNullValue` | `cacheNullValue=false`：null 不进入缓存 |
| `testCacheExpire` | TTL 过期后重新执行方法 |
| `testMultiParam` | 多参数组合缓存 key |
| `testListReturn` | List 类型返回值正确缓存与反序列化 |
| `testCachePenetration` | null 值缓存防穿透（`cacheNullValue=true`） |
| `testDifferentParams` | 不同参数对应独立缓存条目 |
| `testObjectParam` | 对象参数通过 Jackson 序列化生成确定性 key |
| `testArgSpelCacheKey` | `argSpel` 表达式提取部分字段作 key |
| `testBusinessDataCache` | 业务 DTO 对象完整序列化与缓存 |
| `testPerformanceComparison` | 缓存命中性能提升验证（>10x） |
| `testEvict_byArgIndex` | `@BingCacheEvict` 精确 key 失效（`argIndexes`） |
| `testEvict_multiParam_argIndexes` | 多参数部分参与 evict key |
| `testEvict_argIndexMismatch_doesNotEvict` | argIndexes 不匹配时缓存保留 |
| `testEvict_byArgSpel` | SpEL 表达式 key 的配对失效 |
| `testEvict_allEntries_byCacheName` | `allEntries=true` 清除指定 cacheName 所有条目 |
| `testEvict_allEntries_doesNotCollideWithUserDetail` | 前缀碰撞保护：`clearByPrefix("user")` 不误删 `userDetail` |
| `testEvict_allEntries_byKeyPrefix` | `allEntries=true` 配合 `keyPrefix` 批量清除 |
| `testEvict_byKeyPrefix` | keyPrefix 配对精确失效 |
| `testEvict_beforeInvocation_evenOnException` | `beforeInvocation=true`：方法抛异常时缓存仍被清除 |

### AdvancedBingCacheTest（自动化回归，`src/test/`，24 个用例）

| 测试方法 | 验证要点 |
|----------|----------|
| `testOverloadedMethodKeyIsolation` | 重载方法默认前缀隔离（Long vs String） |
| `testOverloadedMethodSameSerializedValue` | 序列化结果相同时不同类型参数仍能隔离 |
| `testL1MissL2HitBackfill` | L1 miss + L2 hit 时回填 L1 |
| `testBackfillWithRemainingTtl` | 回填时携带 L2 剩余 TTL |
| `testNullValueOnlyInL1` | NullValueSentinel 只存 L1 不存 L2 |
| `testClearIncrementsGlobalVersion` | `clear()` 触发全局版本号递增 |
| `testClearByPrefixIncrementsVersion` | `clearByPrefix()` 触发单前缀版本号递增 |
| `testRedisAvailable` | Redis 可用时正常读写 |
| `testL1StillWorksWhenDegraded` | 降级后 L1 仍可正常读写 |
| `testLongKeyTruncation` | 超长 key 自动截断 + SHA-256 后缀 |
| `testDifferentLongParamsDifferentKeys` | 不同超长参数生成不同截断 key |
| `testZeroExpireTimeNeverExpires` | 长过期时间不会提前过期 |
| `testEmptyStringParam` | 空字符串参数 |
| `testSpecialCharParam` | 特殊字符参数 |
| `testChineseParam` | 中文参数 |
| `testConcurrentCacheMissNoStampede` | 20 线程并发访问同一 key |
| `testConcurrentReadWriteConsistency` | 并发读写数据一致性 |
| `testL1MaxTtlDefaultFallback` | L1+L2 模式下自动兜底 300s |
| `testL1EntryRespectsMaxTtl` | L1 条目不超过 l1MaxTtl |
| `testCacheNamePriorityOverKeyPrefix` | cacheName 优先级高于 keyPrefix |
| `testArgSpelIgnoresOtherFields` | argSpel 只用指定字段生成 key |
| `testClearRemovesAllCaches` | `clear()` 清除 L1 和 L2 |
| `testClearByPrefixOnlyRemovesTarget` | `clearByPrefix()` 只清除指定前缀 |
| `testPrefixCollisionProtection` | 前缀碰撞保护：user 不影响 userDetail |

### MultiInstanceCacheTest（手动触发，需去掉 `@Disabled`）

| 测试方法 | 验证要点 |
|----------|----------|
| `testL2CacheSharedAcrossInstances` | 实例1写 L2 → 实例2读命中 |
| `testEvictPropagationAcrossInstances` | 实例1 evict → 实例2通过 Pub/Sub 感知失效 |
| `testConcurrentAccessMultipleInstances` | 3实例并发读写无错误 |
| `testStressMultipleInstances` | 大量混合请求下各实例稳定 |

---

## HTTP 接口速查

启动 Demo 应用后可用以下接口手动验证，或访问 `GET /demo/guide` 获取在线步骤引导。

### `/api` — 基础演示

```
GET  /api/user/{id}           查询用户（含 costMs）
GET  /api/product/{code}      查询商品
GET  /api/order/{id}          查询订单（id>1000 返回 null）
GET  /api/nocache/{key}       无缓存对照接口
GET  /api/instance-info       当前实例信息（port/hostname/pid）
```

### `/demo` — 注解场景演示

```
# 缓存 + 失效
GET    /demo/user/{id}                         查询用户，观察 costMs 判断是否命中缓存
POST   /demo/user/update/{id}?name=Tom         精确 key 失效（argIndexes）
POST   /demo/user/refresh-all                  allEntries=true 批量清除 user 缓存

# argIndexes 部分参数参与 key
GET    /demo/users?category=vip&keyword=a&page=1    keyword 不参与 key
POST   /demo/users/clear?category=vip&keyword=any&page=1  精确清除 category+page
POST   /demo/users/clear/category/{category}         argIndexes 不匹配（保留缓存）

# 缓存穿透防护
GET    /demo/config/{key}                       key=not-exist 返回 null 但被缓存
POST   /demo/config/force-refresh/{key}         beforeInvocation=true，方法失败仍清除缓存

# keyPrefix
GET    /demo/dict/{type}                        查询字典
POST   /demo/dict/update/{type}?value=xxx       keyPrefix 配对失效
POST   /demo/dict/refresh-all                   allEntries+keyPrefix 批量清除

# 手动缓存管理
DELETE /demo/cache?key=user([N:1])              手动清除指定 key
DELETE /demo/cache/prefix/{prefix}              按前缀清除
DELETE /demo/cache/all                          清空所有缓存

# 指引
GET    /demo/guide                              返回各场景的操作步骤
```

### `/cache-test` — 批量快速验证

```
GET  /cache-test/status         缓存服务状态
GET  /cache-test/basic?userId=1001
GET  /cache-test/batch
```

---

## 模块结构

```
bing-cache-test/
├── scripts/
│   └── start-instances.sh          # 多实例启动脚本
├── src/
│   ├── main/
│   │   ├── java/com/example/demo/
│   │   │   ├── DemoApplication.java           Spring Boot 入口
│   │   │   ├── DemoService.java               基础 @BingCache 示例
│   │   │   ├── CacheDemoExamples.java         @BingCache 场景目录（TTL/null/多参数/对象/列表）
│   │   │   ├── BingCacheDemos.java            @BingCacheEvict 场景目录
│   │   │   ├── DemoController.java            /api 接口（含多实例 instance-info）
│   │   │   ├── CacheTestController.java       /cache-test 接口
│   │   │   └── BingCacheDemoController.java   /demo 接口 + 手动缓存管理
│   │   └── resources/
│   │       ├── application.yml                基础配置（Redis + L1 + 对账）
│   │       ├── application-instance1.yml      多实例 port 8081
│   │       ├── application-instance2.yml      多实例 port 8082
│   │       └── application-instance3.yml      多实例 port 8083
│   └── test/
│       └── java/com/example/demo/
│           ├── BingCacheTest.java             单实例功能回归测试（25 个用例）
│           ├── AdvancedBingCacheTest.java     高级功能测试（24 个用例）
│           └── MultiInstanceCacheTest.java    多实例集成测试（需手动启用）
├── MULTI_INSTANCE_TEST.md          多实例测试详细说明
└── README.md                       本文档
```

---

## 缓存配置（application.yml）

```yaml
bing:
  cache:
    caffeine:
      max-size: 1000
      l1-max-ttl: 300        # L1 最大 TTL 5 分钟
    redis:
      enabled: true
      key-prefix: "demo-cache:"
      channel-name: "demo-cache:invalidation"
    reconciliation:
      enabled: true
      interval: 30           # 版本对账间隔（秒）

spring:
  data:
    redis:
      host: cmac-mini
      port: 6379
      password: RedRain123
      database: 0
```

如需在本地 Redis 运行，修改 `host` 为 `localhost` 并去掉 `password` 即可。
