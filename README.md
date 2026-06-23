# Bing Cache

基于 Spring AOP 的方法级缓存组件，通过注解实现透明的数据缓存，支持 L1 本地缓存（Caffeine）和 L2 分布式缓存（Redis）两级架构。

## 特性

- **注解驱动**：`@BingCache` 缓存读取、`@BingCacheEvict` 缓存清除（支持 `@Repeatable` 多缓存协同失效），零侵入业务代码
- **两级缓存**：L1(Caffeine) + L2(Redis) 组合，L1 未命中自动回填并携带 L2 剩余 TTL
- **跨实例失效**：基于 Redis Pub/Sub 广播缓存失效消息，多实例部署时 L1 缓存自动同步
- **版本对账**：定时检查 Redis 版本号变化，补偿 Pub/Sub 消息丢失，确保最终一致性
- **自动降级**：Redis 不可用时自动回退纯 L1 本地缓存模式，恢复后按对账配置处理 L1 脏数据
- **L1 存活限制**：`l1-max-ttl` 限制 L1 条目最大存活时间，作为 Pub/Sub 丢失的兜底保障
- **null 值防穿透**：`cacheNullValue` 属性支持缓存 null 结果，防止缓存穿透
- **SpEL Key 表达式**：`argSpel` 属性支持 SpEL 表达式从参数中选取值生成 key（如 `#user.id`），支持类似 Spring `@Cacheable` 的参数变量
- **确定性 Key**：基于 Jackson 序列化生成 key，不依赖 `toString()`，重启后保持一致
- **自动装配**：Spring Boot Starter 一键引入，根据 classpath 和配置自动选择缓存模式

## 仓库结构

本仓库使用 Maven 多模块结构：

```text
bing-cache/
├── pom.xml              # 父 POM / reactor 聚合工程，统一管理版本、依赖和插件
├── bing-cache-core/     # 核心 starter 源码与单元测试，发布 artifactId 仍为 bing-cache
│   ├── src/main/java/com/bing/cache/
│   └── src/test/java/com/bing/cache/
└── bing-cache-test/     # 集成测试模块，依赖当前 reactor 中的 bing-cache
    ├── src/main/java/com/example/demo/
    └── src/test/java/com/example/demo/
```

对外依赖坐标保持不变：`cn.com.bingbing:bing-cache:1.1-SNAPSHOT`。业务项目继续依赖该坐标即可，不需要依赖 `bing-cache-core` 这个目录名。

## 构建

常用构建命令：

```bash
# 全量构建
mvn clean verify

# 安装父 POM 和所有模块到本地 Maven 仓库
mvn clean install

# 推荐：只安装业务使用所需的 parent + core 到本地 Maven 仓库
mvn clean install -pl bing-cache-core -am

# 只验证核心模块
mvn -pl bing-cache-core -am verify

# 构建集成测试模块，并自动构建核心依赖
mvn -pl bing-cache-test -am verify
```

## 快速开始

### 1. 引入依赖

在项目的 `pom.xml` 中添加：

```xml
<dependency>
  <groupId>cn.com.bingbing</groupId>
  <artifactId>bing-cache</artifactId>
  <version>1.1-SNAPSHOT</version>
</dependency>
```

组件通过 `AutoConfiguration.imports` 自动装配，无需手动配置。

### 2. 使用缓存注解

```java
@Service
public class DictService {

  // 缓存查询结果，1 小时过期
  @BingCache(cacheName = "dict", expireTime = 3600)
  public List<DictVO> getDictList(String dictType) {
    return dictMapper.selectByType(dictType);
  }

  // 使用 SpEL 表达式从对象中取字段作为 key
  @BingCache(cacheName = "user", argSpel = "#user.id")
  public UserVO getUser(UserVO user) {
    return userMapper.selectById(user.getId());
  }

  // 更新数据后清除缓存
  @BingCacheEvict(cacheName = "dict", argIndexes = {0})
  public void updateDict(String dictType, DictVO vo) {
    dictMapper.update(vo);
  }
}
```

## 注解详解

### @BingCache — 缓存读取

标注在查询方法上，方法首次执行后缓存结果，后续调用直接返回缓存值。

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `cacheName` | String | `""` | 缓存名称，用于与 `@BingCacheEvict` 共享同一前缀，优先级最高 |
| `keyPrefix` | String | `""` | 缓存 key 前缀，为空时使用"类全限定名.方法名(参数类型签名)"；`cacheName` 不为空时忽略 |
| `expireTime` | int | `0` | 过期时间（秒），`0` 表示不过期 |
| `argIndexes` | int[] | `{}` | 参与 key 生成的参数索引，空数组表示全部参数参与；`argSpel` 非空时忽略 |
| `argSpel` | String | `""` | SpEL 表达式，从参数中选取值参与 key 生成（如 `#user.id`）；非空时优先于 `argIndexes` |
| `cacheNullValue` | boolean | `false` | 是否缓存 null 结果，设为 `true` 可防止缓存穿透 |

#### cacheName 与 keyPrefix 怎么选？

两者功能上都能自定义缓存 key 前缀，区别在于**语义和使用场景**：

| | cacheName | keyPrefix |
|---|---|---|
| **语义** | "我的缓存叫什么名字" | "我的 key 前缀长什么样" |
| **适用场景** | 需要 `@BingCacheEvict` 配对清除的缓存 | 只需自定义前缀、不需要配对清除的缓存 |
| **配对清除** | `@BingCacheEvict(cacheName = "user")` 天然配对 | 也能配对，但语义不明确 |
| **优先级** | 高（cacheName 不为空时 keyPrefix 被忽略） | 低 |

**简单原则：**
- **需要缓存清除**（读 + 写/删配对）→ 用 `cacheName`
- **只需要缓存、不需要清除** → 用 `keyPrefix` 缩短前缀，或不设置用默认前缀

> 注意：`cacheName` 和 `keyPrefix` 同时设置时，只有 `cacheName` 生效。

#### argSpel SpEL 表达式

`argSpel` 接受 SpEL 表达式，从方法参数中选取值参与 key 生成。表达式中可用的变量（类似 Spring `@Cacheable` 的参数变量）：

| 变量 | 说明 | 示例 |
|------|------|------|
| `#参数名` | 按名称引用方法参数 | `#id`、`#user.id` |
| `#p0` / `#a0` | 按索引引用方法参数（从 0 开始） | `#p0` |
| `#root.method` | 当前方法（`Method` 对象） | `#root.method.name` |
| `#root.methodName` | 方法名 | `#root.methodName` |
| `#root.args` | 参数数组 | `#root.args[0]` |
| `#root.target` | 目标对象 | `#root.target.getClass()` |

> 注意：不支持 `#root.targetClass`、`#caches` 等 Spring Cache 特有变量。

SpEL 表达式求值结果通过 Jackson 序列化为字符串（非基本类型时），null 结果序列化为 `"null"`。

最终 key 格式为 `前缀(spelResult)`，前缀仍由 `cacheName` / `keyPrefix` 决定。

#### null 值处理

**默认行为（不缓存 null）**：`cacheNullValue = false`，方法返回 null 时不缓存，每次调用都会重新执行方法。

**缓存 null（防缓存穿透）**：设置 `cacheNullValue = true` 可以缓存 null 结果，防止大量请求查询不存在的数据时穿透到数据库。

> 内部使用 `BingCacheNullValue.INSTANCE` 占位符存储，因为 Caffeine 不支持缓存 null 值。读取时自动还原为 null 返回给调用方。NullValue 只存 L1 不存 L2（Jackson 无法反序列化包私有类）。
>
> **⚠️ 跨实例限制**：由于 NullValue 不写入 L2（Redis），`cacheNullValue = true` 只能在**本实例**缓存 null 结果防穿透。多实例部署下，**每个实例对同一个不存在的 id 会各自回源一次**，之后该实例即命中本地 L1，不会持续穿透（除非 L1 条目过期或被 LRU 驱逐后重新回源一次）。也就是说 N 个实例对同一个不存在的 id 总共回源 N 次（而非 1 次），之后各实例稳定命中 L1。
>
> 如果希望跨实例共享 null 缓存（每个 id 全集群只回源一次），建议：
> - 在业务层用布隆过滤器拦截不存在的 id
> - 或显式缓存一个"空对象"占位符（如空 `UserVO`，public 类可被 Jackson 序列化写入 L2），而非依赖 null 缓存
>
> 注意：上述限制针对的是**正常业务场景**（少量不存在的 id 被反复查询）。若面临**恶意穿透攻击**（海量不同 id 各查一次），L1 的 `max-size` 容量限制会导致 NullValue 来不及生效，此时必须用布隆过滤器在入口拦截，无论单实例还是多实例、是否写 L2 都无法仅靠缓存解决。

#### 使用示例

```java
// ========== cacheName 场景：需要配对清除 ==========

// 查询 — 缓存结果
@BingCache(cacheName = "user", expireTime = 300)
public UserVO getUserById(Long id) { ... }
// key: user([N:1])

// 更新 — 清除对应缓存（cacheName 相同且参数部分一致即可匹配）
@BingCacheEvict(cacheName = "user", argIndexes = {0})
public void updateUser(Long id, UserVO vo) { ... }
// evict key: user([N:1]) ✓ 匹配

// ========== keyPrefix 场景：只缓存不清除 ==========

// 默认前缀太长（com.example.DictService.getDictList），缩短一下
@BingCache(keyPrefix = "dict", expireTime = 3600)
public List<DictVO> getDictList(String dictType) { ... }
// key: dict([S:sys_config])

// 不过期的静态数据，只需缓存，不需要清除
@BingCache(keyPrefix = "config:sys")
public SystemConfigVO getSystemConfig() { ... }

// ========== 其他用法 ==========

// 基础用法 — 不设置前缀，key 前缀为类名.方法名
@BingCache(expireTime = 3600)
public List<DictVO> getDictList(String dictType) { ... }

// 缓存 null 结果，防止缓存穿透
@BingCache(cacheName = "user", expireTime = 60, cacheNullValue = true)
public UserVO getUserById(Long id) { ... }

// ========== argSpel 场景：SpEL 表达式选取参数 ==========

// 从对象中取字段作为 key（只用 id，不用整个对象）
@BingCache(cacheName = "user", argSpel = "#user.id", expireTime = 300)
public UserVO getUser(UserVO user) { ... }
// key: user(N:1)

// 多参数拼接
@BingCache(cacheName = "order", argSpel = "#userId + ':' + #type")
public Order getOrder(Long userId, String type) { ... }
// key: order(S:1:normal)

// 按索引引用参数（#p0 = 第一个参数，#a0 同义）
@BingCache(cacheName = "user", argSpel = "#p0")
public UserVO getUserById(Long id) { ... }
// key: user(N:1)

// 访问对象方法
@BingCache(cacheName = "user", argSpel = "#user.getName().toLowerCase()")
public UserVO getUser(UserVO user) { ... }
// key: user(S:alice)
```

### @BingCacheEvict — 缓存清除

标注在更新/删除方法上，方法执行后（或执行前）自动清除对应的缓存条目。支持在同一方法上重复使用（`@Repeatable`），用于一个写操作需要清除多个缓存的场景。

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `cacheName` | String | `""` | 缓存名称，需与 `@BingCache` 的 `cacheName` 一致才能匹配 |
| `keyPrefix` | String | `""` | 缓存 key 前缀，同 `@BingCache`；`cacheName` 不为空时忽略 |
| `argIndexes` | int[] | `{}` | 参与 key 生成的参数索引，需与 `@BingCache` 的 `argIndexes` 对应；`argSpel` 非空时忽略 |
| `argSpel` | String | `""` | SpEL 表达式，需与 `@BingCache` 的 `argSpel` 一致才能匹配；非空时优先于 `argIndexes`；`allEntries=true` 时不生效 |
| `allEntries` | boolean | `false` | `true` 时清除所有缓存：有 cacheName/keyPrefix 时只清除该前缀下的缓存；都没有时清空全部缓存 |
| `beforeInvocation` | boolean | `false` | `true` 时在方法执行前清除缓存；默认方法成功后才清除 |

> **cacheName 与 keyPrefix 选择原则同 `@BingCache`**：推荐用 `cacheName` 配对，语义更明确。`cacheName` 不为空时 `keyPrefix` 被忽略。

#### 使用示例

```java
// 更新后清除指定缓存（cacheName 与 @BingCache 对应）
@BingCacheEvict(cacheName = "user:detail")
public void updateUser(Long id, UserVO vo) { ... }

// 删除后清除指定缓存（只取 id 生成 key，与查询方法的 key 匹配）
@BingCacheEvict(cacheName = "user:detail", argIndexes = {0})
public void deleteUser(Long id) { ... }

// 方法执行前清除缓存（即使方法抛异常，缓存也会被清除）
@BingCacheEvict(cacheName = "user:detail", beforeInvocation = true)
public void forceUpdateUser(Long id, UserVO vo) { ... }

// 清空指定 cacheName 下的所有缓存
@BingCacheEvict(cacheName = "user:detail", allEntries = true)
public void refreshAllUsers() { ... }

// 清空全部缓存（不指定 cacheName/keyPrefix）
@BingCacheEvict(allEntries = true)
public void clearAllCache() { ... }

// 使用 argSpel 与 @BingCache 配对（表达式需一致）
@BingCacheEvict(cacheName = "user", argSpel = "#user.id")
public void deleteUser(UserVO user) { ... }
// evict key: user(N:1) ✓ 匹配
```

### 配对使用

`cacheName` 是两个注解之间的桥梁，用来让读写注解共享同一个缓存前缀。

**⚠️ 重要：参数部分也必须一致。** `cacheName` 相同只保证前缀一致，参数部分（`argIndexes` 或 `argSpel`）也必须对应，否则生成的 key 不匹配，evict 清不到缓存；不会自动降级为按 `cacheName` 批量清除。

```java
@Service
public class UserService {

  // 查询 — 缓存结果，key: user([N:1])
  @BingCache(cacheName = "user", expireTime = 300)
  public UserVO getUserById(Long id) {
    return userMapper.selectById(id);
  }

  // 更新 — 清除缓存，argIndexes={0} 只用 id 生成 key → user([N:1]) ✓ 匹配
  @BingCacheEvict(cacheName = "user", argIndexes = {0})
  public void updateUser(Long id, UserVO vo) {
    userMapper.updateById(vo);
  }

  // 删除 — 只有一个参数，不需要 argIndexes，key 自然匹配 → user([N:1]) ✓
  @BingCacheEvict(cacheName = "user")
  public void deleteUser(Long id) {
    userMapper.deleteById(id);
  }

  // 批量刷新 — 只清空 user 缓存，不影响其他 cacheName 的缓存
  @BingCacheEvict(cacheName = "user", allEntries = true)
  public void refreshAllUsers() {
    // 批量操作后，所有 user 前缀的缓存统一失效，dict 等其他缓存不受影响
  }
}
```

> **注意**：查询方法如果使用了 `argIndexes` 或 `argSpel`，清除方法必须设置对应的值。
> 例如查询方法 `@BingCache(cacheName = "user", argSpel = "#user.id")`，清除方法也应为 `@BingCacheEvict(cacheName = "user", argSpel = "#user.id")`。

#### 多缓存协同失效

当一个写操作影响多个缓存时，需要多个 `@BingCacheEvict` 协同清除：

```java
@Service
public class UserService {

  // 用户详情 — 按 id 缓存
  @BingCache(cacheName = "userDetail", expireTime = 300)
  public UserVO getUserDetail(Long id) { ... }

  // 用户列表 — 按 category + page 缓存
  @BingCache(cacheName = "userList", argIndexes = {0, 1}, expireTime = 120)
  public List<UserVO> queryUsers(String category, int page) { ... }

  // 用户统计 — 独立缓存
  @BingCache(cacheName = "userStats", expireTime = 600)
  public UserStatsVO getUserStats() { ... }

  // 更新用户 — 需要清除所有相关缓存
  @BingCacheEvict(cacheName = "userDetail", argIndexes = {0})  // 清除该用户的详情
  @BingCacheEvict(cacheName = "userList", allEntries = true)    // 清除所有列表（无法确定哪些页包含该用户）
  public void updateUser(Long id, UserVO vo) { ... }

  // 新增用户 — 只需清除列表，详情是新 key 无需清除
  @BingCacheEvict(cacheName = "userList", allEntries = true)
  public void createUser(UserVO vo) { ... }

  // 修改用户统计相关字段 — 只清除统计缓存
  @BingCacheEvict(cacheName = "userStats", allEntries = true)
  public void refreshUserStats() { ... }
}
```

> **设计原则**：不同 cacheName 代表独立的缓存空间。更新数据时，根据业务影响范围显式声明需要清除哪些缓存——既不会遗漏（该清的没清），也不会误伤（不该清的清了）。

## 缓存 Key 生成规则

格式：`前缀(参数部分)`

### 前缀优先级

1. **`cacheName`**（最高）— 如 `user`
2. **`keyPrefix`** — 如 `user:detail`
3. **默认** — 类全限定名.方法名(参数类型签名)，如 `com.example.UserService.getUserById(java.lang.Long)`

### 参数选取方式

优先级：`argSpel` > `argIndexes` > 全量参数

| 方式 | 说明 | 示例 |
|------|------|------|
| `argSpel` | SpEL 表达式，从参数中选取值 | `argSpel = "#user.id"` → `user(N:1)` |
| `argIndexes` | 按索引选取整个参数 | `argIndexes = {0, 2}` → `prefix([S:a, N:3])` |
| 全量参数（默认） | 所有参数序列化 | `prefix([S:a, N:2, N:3])` |

### 参数序列化

| 参数类型 | 序列化方式 | 示例 |
|----------|-----------|------|
| null | 字符串 `"null"` | `user([null])` |
| 整数数值 / Boolean / Character / String | 类型前缀 + 值 | `user([N:42])`、`user([B:true])`、`user([S:42])` |
| 数组 | 递归序列化元素 | `user([A:[N:1, N:2, N:3]])` |
| 自定义对象 | Jackson JSON 序列化 | `user([{"id":1,"name":"Alice"}])` |

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
| L2 命中但 TTL 查询返回 `-2` 或 `0` | 跳过 L1 回填，避免创建已经过期或即将过期的本地脏数据 |
| 单 key `evict()` / `@BingCacheEvict(allEntries = false)` | 清除当前实例 L1 和 Redis L2，并通过 Redis Pub/Sub 通知其他实例；不递增版本号，Pub/Sub 丢失时只能依赖 `l1-max-ttl` 兜底 |
| `clear()` / `@BingCacheEvict(allEntries = true)` | 清除当前实例缓存并发布 Pub/Sub；在二级缓存模式下递增版本号，可由版本对账补偿 Pub/Sub 丢失 |

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

L1 未命中但 L2 命中时，L2 的值会回填到 L1。回填时通过 Redis `TTL` 命令获取 L2 的剩余过期时间：

- **remainingTtl > 0**：使用剩余 TTL 回填 L1
- **remainingTtl == -1**：L2 永不过期，L1 也永不过期
- **remainingTtl == -2 或 0**：L2 中 key 已不存在或即将过期，**跳过回填**，避免在 L1 创建永不过期的脏数据

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

1. **版本号存储**：Redis 中维护每个 cacheName 的版本号
   - Key 格式：`bing-cache:__version__:{cacheName}`
   - 全局版本：`bing-cache:__version__:__all__`
   - `clear()` 递增全局版本号；`clearByPrefix(prefix)` 递增对应 cacheName 的版本号
   - **单 key `evict(key)` 不递增版本号**（见下方"对账范围限制"）

2. **定时对账**：`CacheReconciliationService` 每隔 `interval` 秒检查版本号变化
   - 发现全局版本变化 → 清空所有 L1 缓存
   - 发现 cacheName 版本变化 → 按前缀清空 L1 缓存
   - 版本无变化 → 不做任何操作
   - 服务启动后立即执行首次对账（initialDelay=0）

3. **调优建议**：
   - `interval` 越小，一致性越好，但 Redis 开销越大（每次对账 N 次 `GET`，N = 活跃 cacheName 数量）
   - 默认 30 秒适合大多数场景；一致性要求高可缩短到 10 秒
   - 可配合 `l1-max-ttl` 使用，作为双重保障

4. **对账范围限制（重要）**：
   - 对账只补偿 `clear()` 和 `clearByPrefix(prefix)` 的 Pub/Sub 丢失，因为这两类操作会递增版本号。
   - **单 key `evict(key)` 的 Pub/Sub 丢失无法通过对账补偿**。原因：单 key evict 若按 key 写版本号，Redis 中会产生与业务 key 数量等量的 version 键，无限膨胀。
   - 因此单 key evict 的跨实例失效完全依赖 Pub/Sub 实时送达；若 Pub/Sub 丢失，受影响实例只能通过 `l1-max-ttl` 自然过期兜底。
   - 对一致性要求高的单 key 场景，建议：
     - 设置合理的 `l1-max-ttl`（如 300 秒）作为兜底
     - 或改用 `@BingCacheEvict(allEntries = true)` 触发 `clearByPrefix`，享受对账补偿

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

## 配置属性

通过 `application.yml` 配置，前缀为 `bing.cache`：

```yaml
bing:
  cache:
    caffeine:
      max-size: 1000                    # Caffeine Cache 的最大条目数（默认 1000）
      l1-max-ttl: 0                     # L1 最大存活秒数，0 表示不限制（默认 0；L1+L2 模式下 0 会自动兜底为 300）
    redis:
      enabled: true                     # 是否启用 L2 Redis 缓存（默认 true）
      key-prefix: "bing-cache:"         # Redis key 前缀（默认 bing-cache:）
      channel-name: "bing-cache:invalidation"  # Pub/Sub 频道名称（默认 bing-cache:invalidation）
      scan-count: 1000                 # Redis SCAN count hint（默认 1000）
      delete-batch-size: 500           # Redis 批量删除每批 key 数（默认 500）
      use-unlink: true                 # 优先使用 UNLINK 异步删除，失败自动降级 DEL（默认 true）
      failure-log-interval: 30         # Redis 降级期间失败日志限流间隔秒数（默认 30）
    reconciliation:
      enabled: true                     # 是否启用版本对账（默认 true）
      interval: 30                      # 对账间隔秒数（默认 30）
```

### 配置说明

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `bing.cache.caffeine.max-size` | `1000` | Caffeine Cache 的最大条目数 |
| `bing.cache.caffeine.l1-max-ttl` | `0` | L1 最大存活秒数，0 表示不限制。设置后所有 L1 条目过期时间不超过该值，作为 Pub/Sub 丢失或 Redis 不可用时的兜底保障。**L1+L2 模式下若保持 0，组件会自动使用 300 秒作为兜底默认值**（因单 key evict 的 Pub/Sub 丢失无法通过对账补偿）；纯 L1 模式下 0 即不限制 |
| `bing.cache.redis.enabled` | `true` | 是否启用 L2 Redis 缓存。仅在 classpath 存在 Redis 依赖且连接可用时生效；跨实例 `evict()` / `@BingCacheEvict` 失效通知依赖该模式下的 Redis Pub/Sub |
| `bing.cache.redis.key-prefix` | `bing-cache:` | Redis 中缓存 key 的前缀，用于命名空间隔离 |
| `bing.cache.redis.channel-name` | `bing-cache:invalidation` | 缓存失效通知的 Redis Pub/Sub 频道名称，仅在启用 L2 Redis 缓存时生效 |
| `bing.cache.redis.scan-count` | `1000` | Redis SCAN count hint，用于 `clear()` / `clearByPrefix()` 扫描 key |
| `bing.cache.redis.delete-batch-size` | `500` | Redis 清理时每批删除 key 数量，避免一次性删除过多 key |
| `bing.cache.redis.use-unlink` | `true` | 清理 Redis key 时优先使用 `UNLINK` 异步删除；UNLINK 失败时当前批次及后续批次自动降级为 `DEL`；DEL 失败时清理中断并触发降级记录（与 L1 降级流程一致） |
| `bing.cache.redis.failure-log-interval` | `30` | Redis 降级期间重复失败日志的最小输出间隔，单位秒 |
| `bing.cache.reconciliation.enabled` | `true` | 是否启用版本对账，补偿 Pub/Sub 消息丢失 |
| `bing.cache.reconciliation.interval` | `30` | 版本对账间隔秒数 |

### 启用 L2 Redis 缓存

只需确保项目中引入了 `spring-boot-starter-data-redis` 依赖并配置了 Redis 连接：

```xml
<!-- 使用者项目 pom.xml -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

```yaml
# application.yml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

`bing.cache.redis.enabled` 默认为 `true`，只要 Redis 连接可用，自动启用 L1+L2 二级缓存。

### 禁用 L2 Redis 缓存

即使项目中引入了 Redis 依赖，也可以通过配置显式禁用 L2：

```yaml
bing:
  cache:
    redis:
      enabled: false
```

此时回退为纯 L1 本地缓存模式。

### 无 Redis 的项目

如果项目 classpath 中没有 `spring-boot-starter-data-redis`，组件自动以纯 L1 模式运行，无需任何额外配置。`spring-boot-starter-data-redis` 的 scope 为 `provided`，由使用者按需引入。

## 手动管理缓存

注入 `CacheManager` 接口可手动管理缓存：

```java
@Resource
private CacheManager cacheManager;

// 清除指定 key 的缓存
cacheManager.evict("user([N:1])");

// 清除指定 cacheName 下的所有缓存（精确匹配 "user(" 前缀，不会误删 "userDetail" 等）
cacheManager.clearByPrefix("user");

// 清空所有缓存
cacheManager.clear();
```

> 手动 evict 时，key 必须与 `CacheKeyGenerator` 生成的 key 完全一致。建议优先使用 `@BingCacheEvict` 注解方式。

### 通过接口手动清缓存

```java
@RestController
@RequestMapping("/cache")
public class CacheController {

  @Resource
  private CacheManager cacheManager;

  @PostMapping("/clear")
  public String clear() {
    cacheManager.clear();
    return "ok";
  }

  @PostMapping("/evict/{key}")
  public String evict(@PathVariable String key) {
    cacheManager.evict(key);
    return "ok";
  }
}
```

## 日志与调试

开启 DEBUG 日志可查看缓存命中情况：

```yaml
logging:
  level:
    com.bing.cache: DEBUG
```

日志输出示例：

```
DEBUG Cache hit: user([N:1])                        # 缓存命中
DEBUG Cache miss: user([N:1])                       # 缓存未命中
DEBUG Cache put: user([N:1])                        # 缓存写入
DEBUG Cache put (null value): user([N:999])         # null 值缓存写入
DEBUG Cache skip (null result): user([N:999])       # null 结果跳过缓存
DEBUG Cache evict: user([N:1])                      # 缓存清除
DEBUG Cache clear by prefix: user                    # 按前缀清除
DEBUG Cache clear all entries                        # 全局清空
DEBUG L1 cache hit: user([N:1])                     # L1 命中（二级缓存模式）
DEBUG L2 cache hit, backfilling L1: user([N:1])     # L2 命中回填 L1（二级缓存模式）
DEBUG L1+L2 cache miss: user([N:1])                 # L1 和 L2 均未命中（二级缓存模式）
DEBUG Redis cache hit: bing-cache:user([N:1])       # Redis 缓存命中
WARN  Bing Cache: Redis L2 cache has failed 3 consecutive times, degraded to L1-only mode  # Redis 降级
INFO  Bing Cache: Redis L2 cache has recovered from degradation                        # Redis 恢复
```

## 注意事项

1. **自调用失效**：同类内部方法调用不会触发 AOP 代理，缓存注解不生效。需通过 Spring 注入的 Bean 调用。

2. **Redis Pub/Sub 依赖 Redis 二级缓存模式**：跨实例缓存失效通知使用 Redis Pub/Sub 实现。没有 Redis 依赖、Redis 连接不可用，或 `bing.cache.redis.enabled=false` 时，组件以纯 L1 模式运行，`evict()` / `@BingCacheEvict` 只能清除当前 JVM 实例的本地缓存，不能通知其他实例。

3. **Redis Pub/Sub 不保证送达**：失效消息基于 Redis Pub/Sub 广播，属于 fire-and-forget 模式。极端情况下（如网络抖动），其他实例可能收不到失效通知，导致短时间内读到旧数据。**注意：版本对账机制只补偿 `clear()` 和 `clearByPrefix()` 的 Pub/Sub 丢失，单 key `evict()` 的丢失无法补偿**（详见"版本对账机制 → 对账范围限制"）。建议生产环境设置 `l1-max-ttl` 作为兜底。

4. **适用场景**：本组件适用于读多写少、对缓存一致性要求为最终一致的业务场景（如字典数据、用户信息、配置信息等）。不适合频繁更新且要求强一致性的业务。

5. **多实例部署**：L1 本地缓存各实例独立，必须启用 Redis 二级缓存模式后，`@BingCacheEvict` 才会通过 Pub/Sub 通知其他实例清除本地缓存；通知存在毫秒级延迟。如需强一致，请直接查询数据库。

6. **缓存 key 一致性**：手动 `evict()` 时，key 必须和自动生成的完全一致，可从 DEBUG 日志中获取。推荐使用 `@BingCacheEvict` 注解替代手动操作。

7. **Redis 依赖可选**：`spring-boot-starter-data-redis` 的 scope 为 `provided`，由使用者项目按需引入。没有 Redis 依赖时，组件自动以纯 L1 模式运行。

8. **`allEntries` 清除范围**：`@BingCacheEvict(allEntries = true)` 配合 `cacheName` 或 `keyPrefix` 时，只清除该前缀下的缓存条目；都不指定时才全局清空。

9. **`clearByPrefix` 精确匹配语义**：`cacheManager.clearByPrefix(prefix)` 内部匹配 `prefix + "("` 开头的 key，确保只清除指定 cacheName 的缓存，不会误删前缀相同的其他 cacheName（如 `clearByPrefix("user")` 不会误删 `userDetail` 的 key）。Redis SCAN 的 glob 结果会通过 `startsWith` 二次过滤，`prefix` 中的 `*`、`?` 等元字符被当作字面字符处理。

10. **`@BingCacheEvict` 未指定 cacheName/keyPrefix 时会输出警告**：当 `@BingCacheEvict` 既没有设置 `cacheName` 也没有设置 `keyPrefix` 时，默认前缀为当前方法名（如 `updateUser`），而对应的 `@BingCache` 方法默认前缀是其方法名（如 `getUserById`），两者不匹配会导致 evict 静默失效。组件会输出 WARN 日志提醒：

    ```
    WARN @BingCacheEvict on method 'updateUser' has no cacheName or keyPrefix set.
    The default prefix (this method name) may not match @BingCache's method name,
    causing eviction to silently miss the cached key.
    Consider setting cacheName to match @BingCache.
    ```

    建议：始终为 `@BingCacheEvict` 指定 `cacheName`，与对应的 `@BingCache` 保持一致。

## 兼容性说明

| 项目 | 支持情况 | 说明 |
|------|----------|------|
| JDK | Java 17+ | 发布产物使用 `--release 17` 编译，可在 JDK 17 及以上版本运行 |
| Spring Boot | 3.x | 当前测试/依赖管理基线为 Spring Boot 3.5.13；面向 Spring Boot 3.x / Spring Framework 6.x / Jakarta 体系 |
| Spring Boot 2.x | 不支持 | Spring Boot 2.x 仍以 `javax.*` 体系为主，与当前模块使用的 Spring Boot 3 / Jakarta 依赖体系不匹配 |

本地可通过 Maven profiles 验证不同 Spring Boot 3.x 基线：

```bash
mvn clean test -Pboot-3.2
mvn clean test -Pboot-3.3
mvn clean test -Pboot-3.5
```

## 技术栈

- Java 17+
- Spring Boot 3.x（当前测试/依赖管理基线：3.5.13）
- Caffeine（由 Spring Boot BOM 管理版本）
- Spring Data Redis（provided scope，使用者提供）
- AspectJ（由 Spring Boot BOM 管理版本）
- Jackson（key 生成 + Redis 序列化）
- JUnit 5 + Mockito（单元测试）
- Testcontainers（集成测试）
