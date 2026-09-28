# Bing Cache 实现细节

[English](../README.md) | [中文](../README_CN.md)

本文档收录缓存 Key 生成规则与缓存架构实现细节。日常使用（依赖引入、注解用法、配置项）见 [README_CN.md](../README_CN.md)。

## 目录

- [缓存 Key 生成规则](#缓存-key-生成规则)
  - [前缀优先级](#前缀优先级)
  - [参数选取方式](#参数选取方式)
  - [参数序列化](#参数序列化)
  - [Key 长度限制](#key-长度限制)
  - [边界行为说明](#边界行为说明)
- [缓存架构](#缓存架构)
  - [两种缓存模式](#两种缓存模式)
  - [L2 回填 L1 的 TTL 策略](#l2-回填-l1-的-ttl-策略)
  - [跨实例缓存失效（Redis Pub/Sub）](#跨实例缓存失效redis-pubsub)
  - [版本对账机制](#版本对账机制)
    - [对账范围限制（重要）](#对账范围限制重要)
  - [Redis 降级与恢复](#redis-降级与恢复)

## 缓存 Key 生成规则

格式：`前缀(参数部分)`

### 前缀优先级

1. **`cacheName`**（最高）— 如 `user`
2. **`keyPrefix`** — 如 `userDetail`
3. **默认** — 类全限定名.方法名(参数类型签名)，如 `com.example.UserService.getUserById(java.lang.Long)`

> **`group` 是可选的最外层前缀**：设置 `group` 时，key 格式为 `group:prefix(args)`（如 `user:detail(Sg[N:1])`）。`group` 不影响上述优先级，仅作为命名空间前缀拼接在最终 prefix 之前。

### 参数选取方式

优先级：`argSpel` > `argIndexes` > 全量参数

| 方式 | 说明 | 示例 |
|------|------|------|
| `argSpel` | SpEL 表达式，从参数中选取值 | `argSpel = "#user.id"` → `user(Sg[N:1])` |
| `argIndexes` | 按索引选取整个参数 | `argIndexes = {0, 2}` → `prefix([S:a, N:3])` |
| 全量参数（默认） | 所有参数序列化 | `prefix([S:a, N:2, N:3])` |

### 参数序列化

参数部分按"参与 key 生成的参数个数"决定外层形式：

- **单值选取（1 个参数）**：输出 `Sg[...]`，`Sg` 标识 single（单值）
- **多值选取（≥2 个参数）**：输出 `[...]`
- **无参数**：输出空（key 形如 `prefix()`）

这样单值集合参数与多参数不会碰撞：单参数 `List[1,2]` 输出 `Sg[N:1,N:2]`，两参数 `(1,2)` 输出 `[N:1,N:2]`。

| 参数类型 | 元素序列化 | 单值示例 | 多值示例 |
|----------|-----------|---------|---------|
| null | `null` | `user(Sg[null])` | — |
| 整数（Integer/Long/BigInteger 等） | `N:` + 值 | `user(Sg[N:42])` | `user([N:1, N:2])` |
| 字符串 | `S:` + 值 | `user(Sg[S:42])` | `user([S:a, S:b])` |
| Boolean | `B:` + 值 | `user(Sg[B:true])` | — |
| Character | `C:` + 值 | `user(Sg[C:x])` | — |
| 小数（Double/Float/BigDecimal 等） | `D:` + 值 | `user(Sg[D:1.5])` | — |
| 数组 / List | 递归序列化元素 | `user(Sg[[N:1, N:2, N:3]])` | — |
| 自定义对象 | Jackson JSON | `user(Sg[{"id":1,"name":"Alice"}])` | — |

> **数组与 List 在 key 中形式相同**：两者都输出 `[N:1, N:2, N:3]` 形式，业务语义等价。
> 例如 `Long[] {1,2,3}` 与 `List<Long> [1,2,3]` 会命中同一 key，无需区分。
>
> **三种参数选取方式产出一致**：
> - 单值场景：`argSpel="#id"`、`argIndexes={0}`、单参数默认，都输出 `prefix(Sg[N:1])`
> - 多值场景：`argSpel="{#a,#b}"`、`argIndexes={0,1}`、多参数默认，都输出 `prefix([N:1,N:2])`
>
> **argSpel 多值语法约定**：使用 SpEL 列表字面量 `{expr1, expr2, ...}` 表示多值选取，
> 顶层逗号才作为参数分隔，嵌套 `()`/`[]`/`{}` 和字符串字面量中的逗号被忽略，
> 如 `{#a, #b}`、`{new int[]{#a, #b}, #c}`。
> 单元素花括号（如 `{#list}`）会被去壳，按单值处理，输出 `Sg[...]`。
>
> 自定义对象使用 Jackson 序列化而非 `toString()`，确保不同实例和 JVM 重启后 key 一致。
> Jackson 序列化失败时直接抛 `IllegalStateException`（而非降级为 `hashCode()`）。

### Key 长度限制

生成的 key 最大长度为 **256 个字符**。超过时自动截断参数部分并追加截断哈希后缀（`...#` + SHA-256 前 16 位十六进制字符），保证截断后的 key 仍然唯一。

### 边界行为说明

| 场景 | 当前行为 |
|------|----------|
| `argSpel` 返回 null | 参数部分序列化为字符串 `"null"`，例如 `@BingCache(keyPrefix = "user", argSpel = "#id")` 且 `id == null` 时，key 为 `user(null)` |
| `argSpel` 非空且同时配置 `argIndexes` | `argSpel` 优先，`argIndexes` 被忽略，并输出一次 WARN 日志 |
| `argSpel` 求值失败或 key 参数 Jackson 序列化失败 | 直接抛出异常，不执行原方法，也不会写缓存 |
| 业务方法抛异常 | 异常直接向外抛出，不写缓存 |
| 业务方法返回 null 且 `cacheNullValue = false` | 不写缓存，后续调用仍会执行原方法 |
| 业务方法返回 null 且 `cacheNullValue = true` | 写入 L1 null 占位符，后续本实例命中后还原为 null 返回；NullValue 不写入 L2 Redis |
| L2 命中但 TTL 回复返回 `-2` 或 `0` | 跳过 L1 回填，避免创建已经过期或即将过期的本地脏数据 |
| 单 key `evict()` / `@BingCacheEvict(allEntries = false)` 或 `@BingCacheEvict(cacheName = "user", allEntries = false)` | 仅清除当前实例 L1 和 Redis L2 中的这个完整 key，并通过 Redis Pub/Sub 通知其他实例清除同一个 key；**即使配置了 `cacheName`，也不递增 cacheName/group/全局版本号，因此不会触发版本对账去清空同 cacheName 下的所有缓存**。若 Pub/Sub 丢失，只能依赖 `l1-max-ttl` 等待其他实例 L1 中该 key 过期 |
| `clear()` / `clearByPrefix()` / `clearByGroup()` / `@BingCacheEvict(allEntries = true)` | 清除当前实例缓存并发布 Pub/Sub；在二级缓存模式下递增版本号（`clear`→全局版本、`clearByPrefix`→cacheName 版本、`clearByGroup`→group 版本），可由版本对账补偿 Pub/Sub 丢失 |
| 参数序列化值恰好含 `(Sg[` 或 `([` 子串 + 该方法声明 `maxSize > 0` | L1 按前缀限容路由会误判 prefix（启发式 `lastIndexOf` 反推），条目可能落到独立的畸形 prefix 缓存实例，`clearByPrefix(cacheName)` 无法清除它，只能由 `l1-max-ttl` 自然过期兜底。**触发概率极低**——参数值需恰好包含 `(Sg[` 或 `([` 这个特定子串组合 |

## 缓存架构

### 两种缓存模式

#### L1 仅本地缓存（默认，无需 Redis）

```
请求 → @BingCache → L1(Caffeine) 命中?
                       ├─ 是 → 返回缓存值
                       └─ 否 → 执行方法 → 写入 L1 → 返回结果
```

适用场景：单实例部署，或对缓存一致性要求不高的场景。

> **重要限制**：纯 L1 模式没有 Redis Pub/Sub，`evict()` / `@BingCacheEvict` 只能清除**当前 JVM 实例**的本地缓存，无法通知其他实例。多实例部署如果依赖缓存清除保持一致，必须启用 Redis 二级缓存模式。

#### L1 + L2 二级缓存（需要 Redis）

```
请求 → @BingCache → L1(Caffeine) 命中?
                       ├─ 是 → 返回缓存值
                       └─ 否 → L2(Redis) 命中?
                                    ├─ 是 → 回填 L1(携带剩余 TTL) → 返回缓存值
                                    └─ 否 → 执行方法 → 写入 L1 + L2 → 返回结果
```

适用场景：多实例部署，需要跨实例共享缓存和缓存一致性。

### L2 回填 L1 的 TTL 策略

L1 未命中但 L2 命中时，L2 的值会回填到 L1。为了让 L1 条目不比 L2 更长寿，回填需要携带 L2 的剩余过期时间。

`RedisCacheManager.getWithRemainingTtl()` 把 `GET` 与 `TTL` 两条命令放在**同一个 pipeline 往返**内发出，一次网络往返即同时拿到值与剩余 TTL。刻意选择 pipeline 而非 Lua 脚本：两条命令都是只读的，pipeline 不依赖 Redis 的 `EVAL`/`EVALSHA` 能力（ACL 默认禁用脚本、`rename-command` 移除脚本、部分代理网关剥离脚本的场景都照常工作），也不阻塞服务端执行。

代价是值与 TTL 来自两个时刻（其他客户端的命令可能插入其间），这个竞态窗口原本就存在，且已由回填后的 post-check 兜底，因此不必用脚本的原子性去换。

拿到 remainingTtl 后的回填策略：

- **remainingTtl > 0**：使用剩余 TTL 回填 L1
- **remainingTtl == -1**：L2 永不过期，L1 也永不过期
- **remainingTtl == -2 或 0**：L2 中 key 已不存在或即将过期，**跳过回填**，避免在 L1 创建永不过期的脏数据

回填写入 L1 后还有一次 post-check（额外 1 次往返）：再次查询 L2 TTL，若为 -2 说明 key 在读取与 put 之间被 evict，立即清除 L1 中刚写入的旧值。回填路径总往返为 2 次（原先是 3 次：GET → TTL pre-check → TTL post-check）。

### 跨实例缓存失效（Redis Pub/Sub）

> **前提：必须启用 Redis 二级缓存模式。** Pub/Sub 是 Redis 提供的消息通道能力；没有 Redis 依赖、Redis 连接不可用，或 `bing.cache.redis.enabled=false` 时，组件会退化为纯 L1 模式，此时 `evict()` / `@BingCacheEvict` 只影响当前实例，不具备跨实例失效能力。

多实例部署时，任一实例执行 `@BingCacheEvict` 触发的失效操作会通过 Redis Pub/Sub 广播到其他实例：

```
实例 A: @BingCacheEvict → 清除 L2 + 清除 L1 → 发布 Pub/Sub 消息
实例 B: 收到 Pub/Sub 消息 → 清除本地 L1 缓存
实例 C: 收到 Pub/Sub 消息 → 清除本地 L1 缓存
```

- 消息包含 `instanceId`，各实例自动过滤自己发出的消息（自发自滤）
- Pub/Sub 是 fire-and-forget 模式，不保证消息送达；`RedisMessageListenerContainer` 会自动重连
- 频道名称默认 `bing-cache:invalidation`，可通过配置修改

### 版本对账机制

作为 Pub/Sub 消息丢失的补偿，组件提供版本对账机制：

1. **版本号存储**：Redis 中维护每个 cacheName / group 的版本号
   - cacheName 版本：`bing-cache:__version__:{cacheName}`
   - 全局版本：`bing-cache:__version__:__all__`
   - group 版本：`bing-cache:__version__:__group__:{group}`
   - `clear()` 递增全局版本号；`clearByPrefix(prefix)` 递增对应 cacheName 的版本号；`clearByGroup(group)` 递增对应 group 的版本号
   - **单 key `evict(key)` 不递增版本号**（见下方"对账范围限制"）

2. **定时对账**：`CacheReconciliationService` 每隔 `interval` 秒检查版本号变化
   - 发现全局版本变化 → 清空所有 L1 缓存
   - 发现 cacheName 版本变化 → 按前缀清空 L1 缓存
   - 发现 group 版本变化 → 按 group 清空 L1 缓存
   - 版本无变化 → 不做任何操作
   - 服务启动后立即执行首次对账（initialDelay=0）

3. **调优建议**：
   - `interval` 越小，一致性越好，但 Redis 开销越大（每次对账 N 次 `GET`，N = 活跃 cacheName 数量）
   - 默认 30 秒适合大多数场景；一致性要求高可缩短到 10 秒
   - 可配合 `l1-max-ttl` 使用，作为双重保障

#### 对账范围限制（重要）

- 对账补偿 `clear()`、`clearByPrefix(prefix)` 和 `clearByGroup(group)` 的 Pub/Sub 丢失，因为这三类操作会递增版本号。
- **单 key `evict(key)` 的 Pub/Sub 丢失无法通过对账补偿**。原因：单 key evict 若按 key 写版本号，Redis 中会产生与业务 key 数量等量的 version 键，无限膨胀。
- 因此单 key evict 的跨实例失效完全依赖 Pub/Sub 实时送达；若 Pub/Sub 丢失，受影响实例只能通过 `l1-max-ttl` 自然过期兜底。
- 对一致性要求高的单 key 场景，建议：
  - 设置合理的 `l1-max-ttl`（如 300 秒）作为兜底
  - 或改用 `@BingCacheEvict(allEntries = true)` 触发 `clearByPrefix` / `clearByGroup`，享受对账补偿

### Redis 降级与恢复

当 Redis 连续操作失败达到 3 次时，输出 WARN 级别降级日志：

```
WARN  Bing Cache: Redis L2 cache has failed 3 consecutive times, degraded to L1-only mode. Check Redis connectivity.
```

Redis 恢复正常后：

1. 输出 INFO 级别恢复日志：
   ```
   INFO  Bing Cache: Redis L2 cache has recovered from degradation
   ```

2. **L1 脏数据处理策略**：
   - 对账启用（默认）：不立即全量清空 L1，由对账服务在下一个周期按 cacheName 粒度清理，避免恢复瞬间大量回源
   - 对账禁用：立即全量清空 L1，防止 Redis 恢复后脏数据持续暴露

降级期间，所有 L2 操作静默失败，缓存自动退化为纯 L1 模式，不影响业务正常运行。

**Flapping 保护**：降级状态下需**连续 3 次成功**操作才判定 Redis 真正恢复并触发恢复回调。期间任何一次失败都会重置成功计数器。这避免了 Redis 在可用/不可用之间快速抖动时反复触发 `recoveryCallback` 清空 L1、引发缓存雪崩。与降级阈值的 3 次失败形成对称设计。
