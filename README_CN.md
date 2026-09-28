# Bing Cache

[English](README.md) | [中文](README_CN.md)

基于 Spring AOP 的方法级缓存组件，通过注解实现透明的数据缓存，支持 L1 本地缓存（Caffeine）和 L2 分布式缓存（Redis）两级架构。

> **实现细节**（缓存 Key 生成规则、对账机制、降级与恢复等内部机制）见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 特性

- **注解驱动**：`@BingCache` 缓存读取、`@BingCacheEvict` 缓存清除（支持 `@Repeatable` 多缓存协同失效），零侵入业务代码
- **两级缓存**：L1(Caffeine) + L2(Redis) 组合，L1 未命中自动回填并携带 L2 剩余 TTL
- **跨实例失效**：基于 Redis Pub/Sub 广播缓存失效消息，多实例部署时 L1 缓存自动同步
- **版本对账**：定时检查 Redis 版本号变化，补偿 Pub/Sub 消息丢失，确保最终一致性
- **自动降级**：Redis 不可用时自动回退纯 L1 本地缓存模式，恢复后按对账配置处理 L1 脏数据
- **L1 存活限制**：`l1-max-ttl` 限制 L1 条目最大存活时间，作为 Pub/Sub 丢失的兜底保障
- **null 值防穿透**：`cacheNullValue` 属性支持缓存 null 结果，防止缓存穿透
- **L1 前缀限容**：`maxSize` 属性按前缀独立控制 Caffeine 容量，默认 5000，避免高基数方法的条目挤满全局池子驱逐其他热点缓存
- **SpEL Key 表达式**：`argSpel` 属性支持 SpEL 表达式从参数中选取值生成 key（如 `#user.id`），支持类似 Spring `@Cacheable` 的参数变量
- **确定性 Key**：基于 Jackson 序列化生成 key，不依赖 `toString()`，重启后保持一致
- **自动装配**：Spring Boot Starter 一键引入，根据 classpath 和配置自动选择缓存模式

## ⚠️ 一致性须知（使用前必读）

Bing Cache 采用 **最终一致性** 模型，不同失效操作的一致性强度不同，使用前务必了解：

| 失效操作 | 跨实例一致性保障 | Pub/Sub 丢失时的兜底 |
|---|---|---|
| `clear()` / `clearByPrefix()` / `clearByGroup()`（`allEntries=true`） | Pub/Sub + 版本对账（双重） | 版本对账在下一对账周期补偿 ✅ |
| **单 key `evict()`（`allEntries=false`）** | **仅 Pub/Sub（单层）** | **无对账补偿，仅靠 `l1-max-ttl` 自然过期** ⚠️ |

**关键限制**：单 key `evict` 不递增版本号（避免产生与业务 key 等量的版本键导致 Redis 膨胀），因此其跨实例失效**完全依赖 Pub/Sub 实时送达**。若发生网络分区导致 Pub/Sub 持续丢失，被 evict 的脏数据会在其他实例 L1 中驻留最长 `l1-max-ttl`（L1+L2 模式默认 300 秒）。

**实践建议**：
- 对一致性要求高的单 key 更新场景（如"更新用户手机号"），将 `l1-max-ttl` 调到可接受的脏数据窗口（如 60-120 秒）。
- 若 300 秒脏数据窗口不可接受，考虑用 `allEntries=true` 批量清除（走版本对账，一致性更强但清除范围更大）。
- 单 key evict 的详细机制见 [版本对账机制 - 对账范围限制](ARCHITECTURE.md#对账范围限制重要)。

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
| `group` | String | `""` | 缓存分组，用于将多个 cacheName 归类到同一命名空间，支持按 group 批量清除（`@BingCacheEvict(group=..., allEntries=true)`）。设置后 key 格式为 `group:cacheName(args)` |
| `cacheName` | String | `""` | 缓存名称，用于与 `@BingCacheEvict` 共享同一前缀，优先级最高 |
| `keyPrefix` | String | `""` | 缓存 key 前缀，为空时使用"类全限定名.方法名(参数类型签名)"；`cacheName` 不为空时忽略 |
| `expireTime` | int | `0` | 过期时间（秒），`0` 表示不过期 |
| `argIndexes` | int[] | `{}` | 参与 key 生成的参数索引，空数组表示全部参数参与；`argSpel` 非空时忽略 |
| `argSpel` | String | `""` | SpEL 表达式，从参数中选取值参与 key 生成（如 `#user.id`）；非空时优先于 `argIndexes` |
| `cacheNullValue` | boolean | `false` | 是否缓存 null 结果，设为 `true` 可防止缓存穿透 |
| `maxSize` | long | `5000` | L1 最大条目数（按前缀）。默认 5000，每个注解拥有独立 Caffeine 实例；设为 `0` 则使用全局共享缓存。仅限制 L1 本地容量，L2 Redis 不受此限制 |

> **`maxSize` 按前缀生效**：同一 `cacheName` / `keyPrefix` 被多个 `@BingCache` 引用时，容量以**首次写入时**声明的 `maxSize` 为准（懒创建后即固定），后续不同值会被忽略。建议同一前缀始终使用相同的 `maxSize`。

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

#### cacheName 命名约束

**`cacheName` 禁止含 `(` 或 `)`**：这两个字符是 key 生成中参数部分的定界符，cacheName 含此字符会破坏 `clearByPrefix` 边界匹配和 Caffeine 按前缀限容（`maxSize`）的路由。设置时直接抛 `IllegalArgumentException`。建议使用字母、数字、下划线、连字符命名（如 `userDetail`、`userList`）。

**不推荐 `cacheName` 含冒号（`:`）**：`@BingCache` / `@BingCacheEvict` 的 `group` 分组属性使用冒号作为 group 与 cacheName 的层级分隔符，缓存 key 格式为 `group:cacheName(args)`。若 `cacheName` 本身含冒号（如 `cacheName = "user:detail"`），其 key 前缀会与 `group = "user"` + `cacheName = "detail"` 产生的前缀完全相同。此时执行 `@BingCacheEvict(group = "user", allEntries = true)` 触发的 `clearByGroup("user")` 会按 `user:` 前缀匹配清除，**误清那些并未声明属于 `user` 组、只是 cacheName 恰好含冒号的缓存**。含冒号不会抛异常，仅发出 WARN 提示。

> **`keyPrefix` 不做字符校验**：`keyPrefix` 是字面匹配串，需要支持匹配默认前缀（默认前缀格式为 `className.methodName(paramTypes)`，本身含 `(`），因此允许含 `(` / `)` / `:` 等字符。但含冒号存在与 cacheName 同样的 group 碰撞风险，使用 group 时同样应避免。

同理，`group` 本身也不应含冒号。若存在 `group="foo"` 与 `group="foo:bar"` 两个分组，`clearByGroup("foo")` 按 `foo:` 前缀匹配时会误清 `foo:bar:` 下的条目。建议 `group` 使用单词或驼峰命名（如 `user`、`orderDetail`）。

若需要"分组"语义，使用 `group` 属性而非在 `cacheName` 中拼接冒号。

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

**单值与多值**：

- **单值**：普通表达式、拼接表达式或单元素花括号表达式，输出 `Sg[...]`。
  - `#user.id` → `Sg[N:1]`
  - `#userId + ':' + #type` → `Sg[S:1:normal]`
  - `{#list}` → 去壳为 `#list`，等价于单值 → `Sg[[N:1, N:2]]`
- **多值**：使用 SpEL 列表字面量 `{expr1, expr2, ...}`，顶层逗号分隔，嵌套 `()`/`[]`/`{}` 和字符串里的逗号被忽略，输出 `[...]`。
  - `{#a, #b}` → `[N:1, N:2]`
  - `{new int[]{#a, #b}, #c}` → `[[N:1, N:2], N:3]`（两个顶层参数，数组内逗号不影响切分）

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

> **null 值的 TTL 兜底**：null 值表示"数据**当前**不存在"，是一个会过时的临时判定（DB 随时可写使其变为"存在"）。因此当 `cacheNullValue = true` 且 `expireTime <= 0`（永不过期）时，组件不会让 null 占位符永久驻留 L1，而是自动套用 **300 秒**的兜底 TTL，并输出一次 WARN 日志提醒。这避免了"DB 后续插入数据后，本实例因永久缓存的 null 而持续脏读"的问题。此兜底在纯 L1 和 L1+L2 两种模式下均生效；建议为 `cacheNullValue = true` 的方法显式设置一个合理的正数 `expireTime`（如 60 秒），而非依赖兜底值。

#### 使用示例

```java
// ========== cacheName 场景：需要配对清除 ==========

// 查询 — 缓存结果
@BingCache(cacheName = "user", expireTime = 300)
public UserVO getUserById(Long id) { ... }
// key: user(Sg[N:1])

// 更新 — 清除对应缓存（cacheName 相同且参数部分一致即可匹配）
@BingCacheEvict(cacheName = "user", argIndexes = {0})
public void updateUser(Long id, UserVO vo) { ... }
// evict key: user(Sg[N:1]) ✓ 匹配

// ========== keyPrefix 场景：只缓存不清除 ==========

// 默认前缀太长（com.example.DictService.getDictList），缩短一下
@BingCache(keyPrefix = "dict", expireTime = 3600)
public List<DictVO> getDictList(String dictType) { ... }
// key: dict(Sg[S:sys_config])

// 不过期的静态数据，只需缓存，不需要清除
@BingCache(keyPrefix = "configSys")
public SystemConfigVO getSystemConfig() { ... }

// ========== 其他用法 ==========

// 基础用法 — 不设置前缀，key 前缀为类名.方法名
@BingCache(expireTime = 3600)
public List<DictVO> getDictList(String dictType) { ... }

// 缓存 null 结果，防止缓存穿透
@BingCache(cacheName = "user", expireTime = 60, cacheNullValue = true)
public UserVO getUserById(Long id) { ... }

// 限制 L1 缓存容量，避免高基数查询挤满全局池子（如分页查询）
@BingCache(cacheName = "userList", expireTime = 120, maxSize = 100)
public List<UserVO> queryUsers(String category, int page) { ... }

// 不限制容量（0 使用全局共享缓存），适合字典等条目数固定的场景
@BingCache(cacheName = "dict", expireTime = 3600, maxSize = 0)
public List<DictVO> getDictList(String dictType) { ... }

// ========== argSpel 场景：SpEL 表达式选取参数 ==========

// 从对象中取字段作为 key（只用 id，不用整个对象）
@BingCache(cacheName = "user", argSpel = "#user.id", expireTime = 300)
public UserVO getUser(UserVO user) { ... }
// key: user(Sg[N:1])

// 多参数拼接
@BingCache(cacheName = "order", argSpel = "#userId + ':' + #type")
public Order getOrder(Long userId, String type) { ... }
// key: order(Sg[S:1:normal])

// 按索引引用参数（#p0 = 第一个参数，#a0 同义）
@BingCache(cacheName = "user", argSpel = "#p0")
public UserVO getUserById(Long id) { ... }
// key: user(Sg[N:1])

// 访问对象方法
@BingCache(cacheName = "user", argSpel = "#user.getName().toLowerCase()")
public UserVO getUser(UserVO user) { ... }
// key: user(Sg[S:alice])

// 多值选取：方法前两个参数都参与 key 生成
@BingCache(cacheName = "order", argSpel = "{#userId, #type}")
public Order getOrder(Long userId, String type) { ... }
// key: order([N:1, S:normal])
```

### @BingCacheEvict — 缓存清除

标注在更新/删除方法上，方法执行后（或执行前）自动清除对应的缓存条目。支持在同一方法上重复使用（`@Repeatable`），用于一个写操作需要清除多个缓存的场景。

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `group` | String | `""` | 缓存分组，需与 `@BingCache` 的 `group` 一致才能匹配。`allEntries=true` 且仅设置 `group`（无 cacheName/keyPrefix）时，清除整个 group 下的缓存 |
| `cacheName` | String | `""` | 缓存名称，需与 `@BingCache` 的 `cacheName` 一致才能匹配 |
| `keyPrefix` | String | `""` | 缓存 key 前缀，同 `@BingCache`；`cacheName` 不为空时忽略 |
| `argIndexes` | int[] | `{}` | 参与 key 生成的参数索引，需与 `@BingCache` 的 `argIndexes` 对应；`argSpel` 非空时忽略 |
| `argSpel` | String | `""` | SpEL 表达式，需与 `@BingCache` 的 `argSpel` 一致才能匹配；非空时优先于 `argIndexes`；`allEntries=true` 时不生效 |
| `allEntries` | boolean | `false` | `true` 时清除所有缓存：仅 `group` 时清整个 group；有 `cacheName`/`keyPrefix` 时清该前缀；都没有时清空全部 |
| `beforeInvocation` | boolean | `false` | `true` 时在方法执行前清除缓存；默认方法成功后才清除。**⚠️ 方法失败时缓存已被清，数据未变更，后续请求回源，可能引发击穿/雪崩，事务方法上慎用** |

> **cacheName 与 keyPrefix 选择原则同 `@BingCache`**：推荐用 `cacheName` 配对，语义更明确。`cacheName` 不为空时 `keyPrefix` 被忽略。

#### 使用示例

下面所有清除方法都配对同一个查询方法，其 key 为基准：

```java
// 查询 — 基准 key: userDetail(Sg[N:1])
@BingCache(cacheName = "userDetail", expireTime = 300)
public UserVO getUserById(Long id) { ... }
```

各 `@BingCacheEvict` 用法及生成的 key（注意 key 必须与查询方法匹配，否则清不到）：

```java
// 单参数：默认用全部参数生成 key → userDetail(Sg[N:1]) ✓ 匹配
@BingCacheEvict(cacheName = "userDetail")
public void deleteById(Long id) { ... }

// 多参数：用 argIndexes 只取 id 生成 key → userDetail(Sg[N:1]) ✓ 匹配
//        （不加 argIndexes 时两参数都会参与，key 变成 [N:1, {...}] 不匹配）
@BingCacheEvict(cacheName = "userDetail", argIndexes = {0})
public void updateUser(Long id, UserVO vo) { ... }

// 使用 argSpel 取 id 生成 key → userDetail(Sg[N:1]) ✓ 匹配
// （表达式需与 @BingCache 的 argSpel 完全一致）
@BingCacheEvict(cacheName = "userDetail", argSpel = "#vo.id")
public void deleteUser(UserVO vo) { ... }

// 方法执行前清除缓存（即使方法抛异常，缓存也会被清除）
// ⚠️ 注意：若方法失败（异常/事务回滚），数据未变更但缓存已空，后续请求会回源，
//    可能引发击穿/雪崩。仅用于"即使失败也确需清缓存"的场景，事务方法上慎用。
@BingCacheEvict(cacheName = "userDetail", argIndexes = {0}, beforeInvocation = true)
public void forceUpdateUser(Long id, UserVO vo) { ... }

// 清空指定 cacheName 下的所有缓存（忽略参数，清整个 userDetail 前缀）
@BingCacheEvict(cacheName = "userDetail", allEntries = true)
public void refreshAllUsers() { ... }

// 清空全部缓存（不指定 cacheName/keyPrefix）
@BingCacheEvict(allEntries = true)
public void clearAllCache() { ... }
```

> **关键**：`argIndexes` / `argSpel` 决定 evict 的 key 是否与查询 key 匹配，与 `@BingCache` 必须对应。`allEntries=true` 时不按参数清，而是清整个前缀，参数配置被忽略。

### 配对使用

`cacheName` 是两个注解之间的桥梁，用来让读写注解共享同一个缓存前缀。

**⚠️ 重要：参数部分也必须一致。** `cacheName` 相同只保证前缀一致，参数部分（`argIndexes` 或 `argSpel`）也必须对应，否则生成的 key 不匹配，evict 清不到缓存；不会自动降级为按 `cacheName` 批量清除。

```java
@Service
public class UserService {

  // 查询 — 缓存结果，key: user(Sg[N:1])
  @BingCache(cacheName = "user", expireTime = 300)
  public UserVO getUserById(Long id) {
    return userMapper.selectById(id);
  }

  // 更新 — 清除缓存，argIndexes={0} 只用 id 生成 key → user(Sg[N:1]) ✓ 匹配
  @BingCacheEvict(cacheName = "user", argIndexes = {0})
  public void updateUser(Long id, UserVO vo) {
    userMapper.updateById(vo);
  }

  // 删除 — 只有一个参数，不需要 argIndexes，key 自然匹配 → user(Sg[N:1]) ✓
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

当一个写操作影响多个缓存时，有两种方式协同清除：

**方式一：使用 `group` 分组（推荐）**

将相关缓存归入同一 group，写操作用 `@BingCacheEvict(group=..., allEntries=true)` 按命名空间批量清除，无需逐个精确声明 cacheName 与参数：

```java
@Service
public class UserService {

  // 用户详情 — 缓存到 user 组的 detail
  @BingCache(group = "user", cacheName = "detail", expireTime = 300)
  public UserVO getUserDetail(Long id) { ... }
  // key: user:detail(Sg[N:1])

  // 用户列表 — 缓存到 user 组的 list
  @BingCache(group = "user", cacheName = "list", argIndexes = {0, 1}, expireTime = 120)
  public List<UserVO> queryUsers(String category, int page) { ... }
  // key: user:list([S:admin, N:1])

  // 用户统计 — 缓存到 user 组的 stats
  @BingCache(group = "user", cacheName = "stats", expireTime = 600)
  public UserStatsVO getUserStats() { ... }
  // key: user:stats()

  // 用户订单 — 缓存到 user 组的 orders
  @BingCache(group = "user", cacheName = "orders", expireTime = 120)
  public List<OrderVO> getUserOrders(Long userId) { ... }
  // key: user:orders(Sg[N:1])

  // 更新用户 — 一个注解清除 user 组下所有缓存（detail/list/stats/orders 全清）
  @BingCacheEvict(group = "user", allEntries = true)
  public void updateUser(Long id, UserVO vo) { ... }
  // 清除范围：user:* 所有 key

  // 新增订单 — 只清 user 组的 orders 缓存（不影响 detail/list/stats）
  @BingCacheEvict(group = "user", cacheName = "orders", allEntries = true)
  public void createOrder(Long userId, String orderId) { ... }
  // 清除范围：user:orders* 所有 key

  // 刷新统计 — 只清 user 组的 stats 缓存
  @BingCacheEvict(group = "user", cacheName = "stats", allEntries = true)
  public void refreshUserStats() { ... }
  // 清除范围：user:stats* 所有 key
}
```

> **要点**：方式一全程用 `group + allEntries` 按"组 / cacheName"批量清除，不依赖 `argIndexes`/`argSpel` 精确匹配参数。这样即使查询方法的参数选取方式（`argIndexes`/`argSpel`）变化，清除注解也无需同步修改。若只需清单个 key（精确到某用户），见下面"方式二"的 `argIndexes` 用法。

**方式二：使用多个 `@BingCacheEvict`（不使用 group）**

未使用 group 时，需要逐个声明要清除的 cacheName：

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

> **设计原则**：`group` 适合"一个写操作需清除多个相关 cacheName"的场景，用一个注解替代 N 个 `@BingCacheEvict`；不使用 group 时，不同 cacheName 代表独立缓存空间，需显式声明要清除哪些。两种方式都遵循"既不遗漏也不误伤"的原则——`group` 通过命名空间隔离实现，多注解通过显式列举实现。

#### group 清除层级

`group` 提供三层清除粒度：

| 场景 | 注解 | 行为 |
|------|------|------|
| 清除单个缓存 | `@BingCacheEvict(group="user", cacheName="detail", argIndexes={0})` | 清除 `user:detail(Sg[N:1])` |
| 清除 cacheName 下所有缓存 | `@BingCacheEvict(group="user", cacheName="list", allEntries=true)` | 清除 `user:list(` 开头的所有 key |
| 清除整个 group | `@BingCacheEvict(group="user", allEntries=true)` | 清除 `user:` 开头的所有 key（1 次 SCAN + 1 次 Pub/Sub） |
| 清空全部缓存 | `@BingCacheEvict(allEntries=true)` | 清空全部缓存 |

> **`group` 单独使用限制**：`group` 不能单独用于非 `allEntries` 场景（即 `@BingCacheEvict(group="user")` 不带 `allEntries=true` 也不带 `cacheName`/`keyPrefix` 会抛 `IllegalStateException`），因为没有 `cacheName`/`keyPrefix` 无法生成合法的 key 前缀。

## 缓存模式

组件支持两种缓存模式，按 classpath 与配置自动选择：

| 模式 | 条件 | 跨实例失效 | 适用场景 |
|---|---|---|---|
| **L1 仅本地**（Caffeine） | 无 Redis 依赖，或 `bing.cache.redis.enabled=false` | ❌ 仅当前实例 | 单实例部署，或一致性要求不高 |
| **L1 + L2 二级缓存** | Redis 依赖存在且连接可用 | ✅ Redis Pub/Sub + 版本对账 | 多实例部署，需要跨实例共享与保持一致 |

> **重要限制**：纯 L1 模式没有 Redis Pub/Sub，`evict()` / `@BingCacheEvict` 只能清除**当前 JVM 实例**的本地缓存，无法通知其他实例。多实例部署如果依赖缓存清除保持一致，必须启用 Redis 二级缓存模式。

L1 未命中但 L2 命中时，L2 的值会携带**剩余 TTL** 回填 L1，避免 L1 条目比 L2 更长寿（值与 TTL 通过同一次 pipeline 往返取得）。Redis 连续失败 3 次会自动降级为纯 L1 模式，恢复需连续成功 3 次（防抖保护）。

两种模式的数据流、TTL 回填策略、跨实例失效、版本对账与降级恢复的完整机制见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 配置属性

通过 `application.yml` 配置，前缀为 `bing.cache`：

```yaml
bing:
  cache:
    caffeine:
      max-size: 5000                    # 全局共享 Caffeine 实例的最大条目数（默认 5000；@BingCache(maxSize=0) 的条目使用此池子）
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
| `bing.cache.caffeine.max-size` | `5000` | 全局共享 Caffeine 实例的最大条目数。仅对未声明 `maxSize`（或 `maxSize=0`）的缓存生效；注解声明的 `maxSize > 0` 时该前缀拥有独立的 Caffeine 实例，容量以注解为准 |
| `bing.cache.caffeine.l1-max-ttl` | `0` | L1 最大存活秒数，0 表示不限制。设置后所有 L1 条目过期时间不超过该值，作为 Pub/Sub 丢失或 Redis 不可用时的兜底保障。**L1+L2 模式下若保持 0，组件会自动使用 300 秒作为兜底默认值**（因单 key evict 的 Pub/Sub 丢失无法通过对账补偿）；纯 L1 模式下 0 即不限制 |
| `bing.cache.redis.enabled` | `true` | 是否启用 L2 Redis 缓存。仅在 classpath 存在 Redis 依赖且连接可用时生效；跨实例 `evict()` / `@BingCacheEvict` 失效通知依赖该模式下的 Redis Pub/Sub |
| `bing.cache.redis.key-prefix` | `bing-cache:` | Redis 中缓存 key 的前缀，用于命名空间隔离 |
| `bing.cache.redis.channel-name` | `bing-cache:invalidation` | 缓存失效通知的 Redis Pub/Sub 频道名称，仅在启用 L2 Redis 缓存时生效 |
| `bing.cache.redis.scan-count` | `1000` | Redis SCAN count hint，用于 `clear()` / `clearByPrefix()` 扫描 key |
| `bing.cache.redis.delete-batch-size` | `500` | Redis 清理时每批删除 key 数量，避免一次性删除过多 key |
| `bing.cache.redis.use-unlink` | `true` | 清理 Redis key 时优先使用 `UNLINK` 异步删除；UNLINK 失败时当前批次及后续批次自动降级为 `DEL`；DEL 失败时清理中断并触发降级记录（与 L1 降级流程一致） |
| `bing.cache.redis.failure-log-interval` | `30` | Redis 降级期间重复失败日志的最小输出间隔，单位秒 |
| `bing.cache.reconciliation.enabled` | `true` | 是否启用版本对账，补偿 Pub/Sub 消息丢失 |
| `bing.cache.reconciliation.interval` | `30` | 版本对账间隔秒数，取值范围 1~86400（24 小时），超出范围启动时校验失败 |

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
cacheManager.evict("user(Sg[N:1])");

// 清除指定 cacheName 下的所有缓存（精确匹配 "user(" 前缀，不会误删 "userDetail" 等）
cacheManager.clearByPrefix("user");

// 清除指定分组下的所有缓存（匹配 "user:" 开头的整个命名空间）
cacheManager.clearByGroup("user");

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

日志分两层：**切面层**（`CacheAspect` / `CacheEvictAspect`）在纯 L1 和 L1+L2 两种模式下都会输出；**二级缓存层**（`CompositeCacheManager` / `RedisCacheManager`）仅在 L1+L2 模式下额外输出。

切面层日志（两种模式都会输出）：

```
DEBUG Cache hit: user(Sg[N:1])                     # 缓存命中
DEBUG Cache hit (null sentinel): user(Sg[N:999])   # 命中 null 占位符（cacheNullValue=true）
DEBUG Cache miss: user(Sg[N:1])                    # 缓存未命中
DEBUG Cache put: user(Sg[N:1])                    # 缓存写入
DEBUG Cache put (null value): user(Sg[N:999])      # null 值缓存写入
DEBUG Cache skip (null result): user(Sg[N:999])    # null 结果跳过缓存
DEBUG Cache evict: user(Sg[N:1])                   # 单 key 清除
DEBUG Cache clear by prefix: user                  # 按前缀清除（allEntries + cacheName/keyPrefix）
DEBUG Cache clear by group: admin                  # 按 group 清除（allEntries + 仅 group）
DEBUG Cache clear all entries                      # 全局清空（allEntries，无 cacheName/keyPrefix/group）
```

二级缓存层日志（仅 L1+L2 模式额外输出）：

```
DEBUG L1 cache hit: user(Sg[N:1])                     # L1 命中
DEBUG L2 cache hit, backfilling L1: user(Sg[N:1])     # L2 命中并回填 L1
DEBUG L1+L2 cache miss: user(Sg[N:1])                 # L1 和 L2 均未命中
DEBUG Redis cache hit: bing-cache:user(Sg[N:1])       # Redis 命中
DEBUG Cache put (L1+L2): user(Sg[N:1])                # L1+L2 同时写入
DEBUG Cache evict (L2+L1+pub): user(Sg[N:1])          # L2+L1 清除并发布 Pub/Sub
DEBUG Cache clear by prefix (L2+L1+pub): user         # 按前缀清除（L2+L1+Pub/Sub）
DEBUG Cache clear (L2+L1+pub)                         # 全局清空（L2+L1+Pub/Sub）
```

Redis 降级与恢复：

```
WARN  Bing Cache: Redis L2 cache has failed 3 consecutive times, degraded to L1-only mode. Check Redis connectivity.  # 连续失败 3 次降级
WARN  Bing Cache: Redis L2 cache still degraded. ...                                                                  # 降级期间按 failure-log-interval 限流的摘要日志
INFO  Bing Cache: Redis L2 cache has recovered from degradation                                                        # 连续成功 3 次恢复
```

## 注意事项

1. **自调用失效**：同类内部方法调用不会触发 AOP 代理，缓存注解不生效。需通过 Spring 注入的 Bean 调用。

2. **Redis Pub/Sub 依赖 Redis 二级缓存模式**：跨实例缓存失效通知使用 Redis Pub/Sub 实现。没有 Redis 依赖、Redis 连接不可用，或 `bing.cache.redis.enabled=false` 时，组件以纯 L1 模式运行，`evict()` / `@BingCacheEvict` 只能清除当前 JVM 实例的本地缓存，不能通知其他实例。

3. **Redis Pub/Sub 不保证送达**：失效消息基于 Redis Pub/Sub 广播，属于 fire-and-forget 模式。极端情况下（如网络抖动），其他实例可能收不到失效通知，导致短时间内读到旧数据。**注意：版本对账机制只补偿 `clear()`、`clearByPrefix()` 和 `clearByGroup()` 的 Pub/Sub 丢失，单 key `evict()` 的丢失无法补偿**（详见 [对账范围限制](ARCHITECTURE.md#对账范围限制重要)）。建议生产环境设置 `l1-max-ttl` 作为兜底。

4. **适用场景**：本组件适用于读多写少、对缓存一致性要求为最终一致的业务场景（如字典数据、用户信息、配置信息等）。不适合频繁更新且要求强一致性的业务。

5. **多实例部署**：L1 本地缓存各实例独立，必须启用 Redis 二级缓存模式后，`@BingCacheEvict` 才会通过 Pub/Sub 通知其他实例清除本地缓存；通知存在毫秒级延迟。如需强一致，请直接查询数据库。

6. **缓存 key 一致性**：手动 `evict()` 时，key 必须和自动生成的完全一致，可从 DEBUG 日志中获取。推荐使用 `@BingCacheEvict` 注解替代手动操作。

7. **Redis 依赖可选**：`spring-boot-starter-data-redis` 的 scope 为 `provided`，由使用者项目按需引入。没有 Redis 依赖时，组件自动以纯 L1 模式运行。

8. **`allEntries` 清除范围**：`@BingCacheEvict(allEntries = true)` 配合 `cacheName` 或 `keyPrefix` 时，只清除该前缀下的缓存条目；仅指定 `group`（无 cacheName/keyPrefix）时按 group 清除；都不指定时才全局清空。

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

## 仓库结构与构建

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
