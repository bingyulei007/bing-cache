# Bing Cache

[English](README.md) | [中文](README_CN.md)

A method-level cache component built on Spring AOP. Transparent caching through annotations, with a two-level architecture: L1 local cache (Caffeine) and L2 distributed cache (Redis).

> **Implementation details** (cache key format, reconciliation, degradation and recovery internals) live in [ARCHITECTURE.md](ARCHITECTURE.md) — Chinese only for now.

## Features

- **Annotation-driven**: `@BingCache` for caching reads, `@BingCacheEvict` for invalidation (repeatable, so one write can invalidate several caches) — zero intrusion into business code
- **Two-level cache**: L1 (Caffeine) + L2 (Redis); an L1 miss is backfilled from L2 together with the L2 entry's remaining TTL
- **Cross-instance invalidation**: cache invalidation is broadcast over Redis Pub/Sub, so L1 stays in sync across instances
- **Version reconciliation**: a periodic check of Redis version numbers compensates for lost Pub/Sub messages, providing eventual consistency
- **Automatic degradation**: falls back to L1-only mode when Redis is unavailable, and reconciles L1 dirty data on recovery
- **L1 entry cap**: `l1-max-ttl` bounds how long an L1 entry can live — the backstop when Pub/Sub messages are lost
- **Null-value protection**: `cacheNullValue` caches null results to prevent cache penetration
- **Per-prefix L1 sizing**: `maxSize` caps Caffeine capacity independently per prefix (default 5000), so a high-cardinality method cannot evict everyone else's hot entries
- **SpEL key expressions**: `argSpel` selects values from arguments to build the key (e.g. `#user.id`), using the same argument variables as Spring's `@Cacheable`
- **Deterministic keys**: built from Jackson serialization rather than `toString()`, so keys survive JVM restarts
- **Auto-configuration**: a Spring Boot starter; the cache mode is selected from the classpath and configuration

## ⚠️ Consistency Model (read this first)

Bing Cache is **eventually consistent**, and different invalidation operations have different consistency strengths:

| Invalidation operation | Cross-instance guarantee | Backstop when Pub/Sub is lost |
|---|---|---|
| `clear()` / `clearByPrefix()` / `clearByGroup()` (`allEntries=true`) | Pub/Sub **+** version reconciliation (two layers) | Reconciliation compensates on the next cycle ✅ |
| **Single-key `evict()` (`allEntries=false`)** | **Pub/Sub only (one layer)** | **No reconciliation — relies purely on `l1-max-ttl` expiry** ⚠️ |

**Key limitation**: a single-key `evict` does not increment any version counter (that would create one version key per business key and bloat Redis), so its cross-instance invalidation **depends entirely on the Pub/Sub message arriving**. If a network partition causes Pub/Sub messages to be lost, the evicted stale value stays in other instances' L1 for at most `l1-max-ttl` (300 seconds by default in L1+L2 mode).

**Recommendations**:
- For single-key updates that demand strong consistency (e.g. "update a user's phone number"), lower `l1-max-ttl` to a dirty-data window you can accept (e.g. 60–120 seconds).
- If a 300-second window is unacceptable, use `allEntries=true` bulk clearing instead — it goes through version reconciliation, which is stronger but clears a wider range.
- The full mechanism is described in [reconciliation scope limits](ARCHITECTURE.md#对账范围限制重要).

## Quick Start

### 1. Add the dependency

```xml
<dependency>
  <groupId>cn.com.bingbing</groupId>
  <artifactId>bing-cache</artifactId>
  <version>1.1-SNAPSHOT</version>
</dependency>
```

The component is wired through `AutoConfiguration.imports`; no manual configuration is required.

### 2. Use the annotations

```java
@Service
public class DictService {

  // Cache the query result for 1 hour
  @BingCache(cacheName = "dict", expireTime = 3600)
  public List<DictVO> getDictList(String dictType) {
    return dictMapper.selectByType(dictType);
  }

  // Use a SpEL expression to pick a field from an object for the key
  @BingCache(cacheName = "user", argSpel = "#user.id")
  public UserVO getUser(UserVO user) {
    return userMapper.selectById(user.getId());
  }

  // Invalidate the cache after an update
  @BingCacheEvict(cacheName = "dict", argIndexes = {0})
  public void updateDict(String dictType, DictVO vo) {
    dictMapper.update(vo);
  }
}
```

## Annotation Reference

### @BingCache — cache a read

Put it on a query method. The result is cached after the first execution; later calls return the cached value.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `group` | String | `""` | Cache group. Places several cache names in one namespace and enables bulk group clearing (`@BingCacheEvict(group=..., allEntries=true)`). When set, the key format is `group:cacheName(args)` |
| `cacheName` | String | `""` | Cache name. Shares a prefix with `@BingCacheEvict`; highest priority |
| `keyPrefix` | String | `""` | Cache key prefix. When empty, `fully.qualified.ClassName.methodName(paramTypes)` is used. Ignored when `cacheName` is set |
| `expireTime` | int | `0` | Expiry in seconds; `0` means no expiry |
| `argIndexes` | int[] | `{}` | Argument indexes that take part in key generation. An empty array means all arguments participate. Ignored when `argSpel` is set |
| `argSpel` | String | `""` | SpEL expression selecting values from arguments for key generation (e.g. `#user.id`). Takes priority over `argIndexes` |
| `cacheNullValue` | boolean | `false` | Cache null results. Set `true` to prevent cache penetration |
| `maxSize` | long | `5000` | Max L1 entry count **per prefix**. Each annotation owns a separate Caffeine instance; `0` uses the shared global cache. Limits L1 only — L2 (Redis) is unbounded |

> **`maxSize` takes effect per prefix**: when the same `cacheName` / `keyPrefix` is referenced by several `@BingCache` annotations, the effective capacity is the `maxSize` declared on the **first write** (the instance is created lazily and then fixed); later differing values are ignored. Use the same `maxSize` for a given prefix.

#### cacheName vs keyPrefix?

Both customize the key prefix; they differ in **semantics and use case**:

| | cacheName | keyPrefix |
|---|---|---|
| **Semantics** | "what my cache is called" | "what my key prefix looks like" |
| **Use case** | Caches that must be invalidated by a paired `@BingCacheEvict` | Caches that only need a custom prefix, with no paired invalidation |
| **Paired invalidation** | `@BingCacheEvict(cacheName = "user")` pairs naturally | Possible, but semantically fuzzy |
| **Priority** | High (when set, `keyPrefix` is ignored) | Low |

**Rule of thumb**:
- **Need invalidation** (a read paired with a write/delete) → use `cacheName`
- **Cache only, no invalidation** → use `keyPrefix` to shorten the prefix, or set nothing and accept the default

> Note: when both `cacheName` and `keyPrefix` are set, only `cacheName` takes effect.

#### cacheName naming constraints

**`cacheName` must not contain `(` or `)`**: these characters delimit the argument part of a generated key. A cache name containing them breaks `clearByPrefix` boundary matching and the routing used for per-prefix `maxSize`. Setting one throws `IllegalArgumentException`. Prefer letters, digits, underscores and hyphens (e.g. `userDetail`, `userList`).

**A colon (`:`) in `cacheName` is discouraged**: `group` uses `:` as the separator between group and cache name, and the key format is `group:cacheName(args)`. A `cacheName` that itself contains a colon (e.g. `cacheName = "user:detail"`) produces **exactly the same** prefix as `group = "user"` + `cacheName = "detail"`. Then `@BingCacheEvict(group = "user", allEntries = true)` triggers `clearByGroup("user")`, which matches on the `user:` prefix and **clears caches that never declared membership in the `user` group** — they merely happened to use a colon. A colon does not throw; a WARN is logged.

> **`keyPrefix` is not character-validated**: it is a literal match string and must be able to express a default prefix (whose form is `className.methodName(paramTypes)` and therefore contains `(`), so `(`, `)` and `:` are all allowed. It carries the same group-collision risk from a colon, so avoid colons there too when using `group`.

The same applies to `group` itself: it should not contain a colon. If both `group="foo"` and `group="foo:bar"` exist, `clearByGroup("foo")` matches the `foo:` prefix and will wrongly clear entries under `foo:bar:`. Prefer single words or camel case for `group` (e.g. `user`, `orderDetail`).

If you want grouping semantics, use the `group` attribute rather than concatenating colons into `cacheName`.

#### argSpel (SpEL expressions)

`argSpel` accepts a SpEL expression that selects values from the method arguments to build the key. Available variables mirror Spring's `@Cacheable` argument variables:

| Variable | Description | Example |
|---|---|---|
| `#argName` | Reference a method argument by name | `#id`, `#user.id` |
| `#p0` / `#a0` | Reference a method argument by index (0-based) | `#p0` |
| `#root.method` | The current `Method` object | `#root.method.name` |
| `#root.methodName` | Method name | `#root.methodName` |
| `#root.args` | Argument array | `#root.args[0]` |
| `#root.target` | Target object | `#root.target.getClass()` |

> Note: Spring Cache-specific variables such as `#root.targetClass` and `#caches` are not supported.

**Single value vs multiple values**:

- **Single value** — a plain expression, a concatenation, or a single-element brace expression. Rendered as `Sg[...]`.
  - `#user.id` → `Sg[N:1]`
  - `#userId + ':' + #type` → `Sg[S:1:normal]`
  - `{#list}` → the braces are stripped, treated as single value → `Sg[[N:1, N:2]]`
- **Multiple values** — use a SpEL list literal `{expr1, expr2, ...}`. Top-level commas separate values; commas inside nested `()` / `[]` / `{}` and inside string literals are ignored. Rendered as `[...]`.
  - `{#a, #b}` → `[N:1, N:2]`
  - `{new int[]{#a, #b}, #c}` → `[[N:1, N:2], N:3]` (two top-level values; commas inside the array do not split)

The SpEL result is serialized to a string via Jackson (for non-primitive types); a null result serializes to `"null"`.

The final key format is `prefix(spelResult)`, where the prefix still comes from `cacheName` / `keyPrefix`.

#### Null value handling

**Default (nulls are not cached)**: with `cacheNullValue = false`, a null return value is not cached and the method runs again on every call.

**Caching null (anti-penetration)**: set `cacheNullValue = true` to cache null results and prevent a flood of lookups for non-existent data from reaching the database.

> Internally a `BingCacheNullValue.INSTANCE` placeholder is used, because Caffeine cannot store null. On read it is transparently turned back into null for the caller. The null placeholder is stored in L1 only, never in L2 (Jackson cannot deserialize a package-private class).
>
> **⚠️ Cross-instance limitation**: because the null placeholder is not written to L2 (Redis), `cacheNullValue = true` prevents penetration **only on the instance that cached it**. In a multi-instance deployment, **each instance falls back to the source once per non-existent id**; after that, the instance hits its own L1 and stops penetrating (unless the L1 entry expires or is evicted by LRU, in which case it falls back once more). In other words, N instances produce N source lookups in total for the same non-existent id rather than 1, after which each instance reliably hits L1.
>
> To share the null cache across instances (one source lookup per id for the whole cluster), prefer:
> - filtering non-existent ids with a Bloom filter in the business layer, or
> - explicitly caching an "empty object" placeholder (e.g. an empty `UserVO`; a public class can be serialized by Jackson and written to L2) instead of relying on null caching
>
> Note: this limitation concerns **ordinary traffic** (a small number of repeatedly queried non-existent ids). Against a **cache penetration attack** (a flood of distinct ids, each queried once), L1's `max-size` bound means the null placeholder never gets a chance to work — you must block ids at the entry point with a Bloom filter. No cache-layer setting can solve this, single- or multi-instance, with or without L2.

> **TTL backstop for null values**: a null value means "the data is **currently** absent", which is a temporary judgement that goes stale (the row can be inserted at any moment). So when `cacheNullValue = true` and `expireTime <= 0` (no expiry), the component does not let the null placeholder live in L1 forever: it applies a **300-second** fallback TTL and logs a single WARN. This prevents "the row is inserted later, but this instance keeps reading null forever because it cached the absence permanently". The backstop applies in both L1-only and L1+L2 mode. Prefer setting an explicit positive `expireTime` (e.g. 60 seconds) for `cacheNullValue = true` methods rather than relying on the fallback.

#### Examples

```java
// ========== cacheName: needs paired invalidation ==========

// Read — cache the result
@BingCache(cacheName = "user", expireTime = 300)
public UserVO getUserById(Long id) { ... }
// key: user(Sg[N:1])

// Update — invalidate the matching entry (same cacheName and matching argument part)
@BingCacheEvict(cacheName = "user", argIndexes = {0})
public void updateUser(Long id, UserVO vo) { ... }
// evict key: user(Sg[N:1]) ✓ matches

// ========== keyPrefix: cache only, no invalidation ==========

// The default prefix is too long (com.example.DictService.getDictList) — shorten it
@BingCache(keyPrefix = "dict", expireTime = 3600)
public List<DictVO> getDictList(String dictType) { ... }
// key: dict(Sg[S:sys_config])

// Static data that never expires: cache it, never invalidate it
@BingCache(keyPrefix = "configSys")
public SystemConfigVO getSystemConfig() { ... }

// ========== Other usages ==========

// Basic — no prefix, so the key prefix is ClassName.methodName
@BingCache(expireTime = 3600)
public List<DictVO> getDictList(String dictType) { ... }

// Cache a null result to prevent cache penetration
@BingCache(cacheName = "user", expireTime = 60, cacheNullValue = true)
public UserVO getUserById(Long id) { ... }

// Bound L1 capacity so high-cardinality queries (e.g. pagination) cannot fill the pool
@BingCache(cacheName = "userList", expireTime = 120, maxSize = 100)
public List<UserVO> queryUsers(String category, int page) { ... }

// No capacity bound (0 uses the shared global cache) — suits fixed-cardinality data like dictionaries
@BingCache(cacheName = "dict", expireTime = 3600, maxSize = 0)
public List<DictVO> getDictList(String dictType) { ... }

// ========== argSpel: select arguments with a SpEL expression ==========

// Pick a field from an object for the key (just the id, not the whole object)
@BingCache(cacheName = "user", argSpel = "#user.id", expireTime = 300)
public UserVO getUser(UserVO user) { ... }
// key: user(Sg[N:1])

// Concatenate several arguments
@BingCache(cacheName = "order", argSpel = "#userId + ':' + #type")
public Order getOrder(Long userId, String type) { ... }
// key: order(Sg[S:1:normal])

// Reference an argument by index (#p0 = first argument, #a0 is a synonym)
@BingCache(cacheName = "user", argSpel = "#p0")
public UserVO getUserById(Long id) { ... }
// key: user(Sg[N:1])

// Call a method on an object
@BingCache(cacheName = "user", argSpel = "#user.getName().toLowerCase()")
public UserVO getUser(UserVO user) { ... }
// key: user(Sg[S:alice])

// Multiple values: both of the first two arguments take part in the key
@BingCache(cacheName = "order", argSpel = "{#userId, #type}")
public Order getOrder(Long userId, String type) { ... }
// key: order([N:1, S:normal])
```

### @BingCacheEvict — invalidate

Put it on an update/delete method. Matching entries are cleared after (or before) the method runs. The annotation is `@Repeatable`, so one write can invalidate several caches at once.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `group` | String | `""` | Cache group. Must match `@BingCache`'s `group` to line up. With `allEntries=true` and only `group` set (no cacheName/keyPrefix), the whole group is cleared |
| `cacheName` | String | `""` | Cache name. Must match `@BingCache`'s `cacheName` to line up |
| `keyPrefix` | String | `""` | Cache key prefix, same as `@BingCache`; ignored when `cacheName` is set |
| `argIndexes` | int[] | `{}` | Argument indexes for key generation. Must correspond to `@BingCache`'s `argIndexes`. Ignored when `argSpel` is set |
| `argSpel` | String | `""` | SpEL expression. Must be identical to `@BingCache`'s `argSpel` to line up. Takes priority over `argIndexes`. Not used when `allEntries=true` |
| `allEntries` | boolean | `false` | `true` clears whole namespaces: only `group` → clear that group; `cacheName`/`keyPrefix` → clear that prefix; neither → clear everything |
| `beforeInvocation` | boolean | `false` | `true` clears before the method runs. By default the cache is cleared only after the method succeeds. **⚠️ On failure the cache is already empty while the data is unchanged, so subsequent requests fall through to the source — this can trigger a stampede/avalanche. Use with care on transactional methods** |

> **Choose `cacheName` over `keyPrefix` here too**: pairing on `cacheName` is semantically clearer. When `cacheName` is set, `keyPrefix` is ignored.

#### Examples

All the invalidation methods below pair with one query method whose key is the reference:

```java
// Read — reference key: userDetail(Sg[N:1])
@BingCache(cacheName = "userDetail", expireTime = 300)
public UserVO getUserById(Long id) { ... }
```

Each `@BingCacheEvict` usage and the key it generates (the key must match the query method, otherwise nothing is cleared):

```java
// Single argument: all arguments are used by default → userDetail(Sg[N:1]) ✓ matches
@BingCacheEvict(cacheName = "userDetail")
public void deleteById(Long id) { ... }

// Multiple arguments: use argIndexes to take just the id → userDetail(Sg[N:1]) ✓ matches
//        (without argIndexes both arguments participate, the key becomes [N:1, {...}] and does not match)
@BingCacheEvict(cacheName = "userDetail", argIndexes = {0})
public void updateUser(Long id, UserVO vo) { ... }

// Use argSpel to take the id → userDetail(Sg[N:1]) ✓ matches
// (the expression must be identical to @BingCache's argSpel)
@BingCacheEvict(cacheName = "userDetail", argSpel = "#vo.id")
public void deleteUser(UserVO vo) { ... }

// Clear before the method runs (the cache is cleared even if the method throws)
// ⚠️ If the method fails (exception / transaction rollback) the data is unchanged but the cache is
//    empty, so subsequent requests fall through to the source and may trigger a stampede/avalanche.
//    Use only where "clear even on failure" is genuinely required; be careful on transactional methods.
@BingCacheEvict(cacheName = "userDetail", argIndexes = {0}, beforeInvocation = true)
public void forceUpdateUser(Long id, UserVO vo) { ... }

// Clear every entry under the cache name (arguments ignored, whole userDetail prefix)
@BingCacheEvict(cacheName = "userDetail", allEntries = true)
public void refreshAllUsers() { ... }

// Clear everything (no cacheName/keyPrefix)
@BingCacheEvict(allEntries = true)
public void clearAllCache() { ... }
```

> **Critical**: `argIndexes` / `argSpel` decide whether the evict key matches the query key, and must correspond to `@BingCache`. With `allEntries=true` arguments are ignored and the whole prefix is cleared.

### Pairing read and write

`cacheName` is the bridge between the two annotations: it lets the read and write annotations share a cache prefix.

**⚠️ The argument part must match too.** A shared `cacheName` only guarantees the prefix matches; the argument part (`argIndexes` or `argSpel`) must correspond too, otherwise the generated keys differ, the eviction misses, and it does **not** silently degrade to a bulk clear by `cacheName`.

```java
@Service
public class UserService {

  // Read — cache the result, key: user(Sg[N:1])
  @BingCache(cacheName = "user", expireTime = 300)
  public UserVO getUserById(Long id) {
    return userMapper.selectById(id);
  }

  // Update — clear the cache; argIndexes={0} builds the key from the id alone → user(Sg[N:1]) ✓ matches
  @BingCacheEvict(cacheName = "user", argIndexes = {0})
  public void updateUser(Long id, UserVO vo) {
    userMapper.updateById(vo);
  }

  // Delete — one argument only, no argIndexes needed, the key matches naturally → user(Sg[N:1]) ✓
  @BingCacheEvict(cacheName = "user")
  public void deleteUser(Long id) {
    userMapper.deleteById(id);
  }

  // Bulk refresh — clears only the user cache, leaving other cache names alone
  @BingCacheEvict(cacheName = "user", allEntries = true)
  public void refreshAllUsers() {
    // After a bulk operation every user-prefixed entry is invalidated; dict and others are untouched
  }
}
```

> **Note**: if the query method uses `argIndexes` or `argSpel`, the invalidation method must set the matching value.
> For example, if the query is `@BingCache(cacheName = "user", argSpel = "#user.id")`, the invalidation should be `@BingCacheEvict(cacheName = "user", argSpel = "#user.id")`.

#### Invalidating several caches from one write

When one write affects several caches, there are two ways to coordinate the invalidation.

**Option 1: group them (recommended)**

Put related caches in one group and let the write invalidate the whole namespace with `@BingCacheEvict(group=..., allEntries=true)`, without declaring every cache name and argument combination:

```java
@Service
public class UserService {

  // User detail — cached under user/detail
  @BingCache(group = "user", cacheName = "detail", expireTime = 300)
  public UserVO getUserDetail(Long id) { ... }
  // key: user:detail(Sg[N:1])

  // User list — cached under user/list
  @BingCache(group = "user", cacheName = "list", argIndexes = {0, 1}, expireTime = 120)
  public List<UserVO> queryUsers(String category, int page) { ... }
  // key: user:list([S:admin, N:1])

  // User statistics — cached under user/stats
  @BingCache(group = "user", cacheName = "stats", expireTime = 600)
  public UserStatsVO getUserStats() { ... }
  // key: user:stats()

  // User orders — cached under user/orders
  @BingCache(group = "user", cacheName = "orders", expireTime = 120)
  public List<OrderVO> getUserOrders(Long userId) { ... }
  // key: user:orders(Sg[N:1])

  // Update a user — one annotation clears everything under the user group (detail/list/stats/orders)
  @BingCacheEvict(group = "user", allEntries = true)
  public void updateUser(Long id, UserVO vo) { ... }
  // clears: every user:* key

  // Create an order — clears only user/orders, leaving detail/list/stats alone
  @BingCacheEvict(group = "user", cacheName = "orders", allEntries = true)
  public void createOrder(Long userId, String orderId) { ... }
  // clears: every user:orders* key

  // Refresh statistics — clears only user/stats
  @BingCacheEvict(group = "user", cacheName = "stats", allEntries = true)
  public void refreshUserStats() { ... }
  // clears: every user:stats* key
}
```

> **Why this helps**: option 1 relies on `group + allEntries` throughout, matching by "group / cache name" instead of depending on `argIndexes` / `argSpel` to match arguments exactly. Even if a query method later changes how it selects arguments, the invalidation annotations need no update. If you need to clear exactly one entry (one specific user), use the `argIndexes` form in option 2 below.

**Option 2: several `@BingCacheEvict` (no group)**

Without a group, name every cache to clear explicitly:

```java
@Service
public class UserService {

  // User detail — cached by id
  @BingCache(cacheName = "userDetail", expireTime = 300)
  public UserVO getUserDetail(Long id) { ... }

  // User list — cached by category + page
  @BingCache(cacheName = "userList", argIndexes = {0, 1}, expireTime = 120)
  public List<UserVO> queryUsers(String category, int page) { ... }

  // User statistics — independent cache
  @BingCache(cacheName = "userStats", expireTime = 600)
  public UserStatsVO getUserStats() { ... }

  // Update a user — must clear every related cache
  @BingCacheEvict(cacheName = "userDetail", argIndexes = {0})  // clear this user's detail
  @BingCacheEvict(cacheName = "userList", allEntries = true)    // clear all lists (can't know which pages contain the user)
  public void updateUser(Long id, UserVO vo) { ... }

  // Create a user — only the list needs clearing; a new detail key needs none
  @BingCacheEvict(cacheName = "userList", allEntries = true)
  public void createUser(UserVO vo) { ... }

  // Change a statistics-related field — clear only the statistics cache
  @BingCacheEvict(cacheName = "userStats", allEntries = true)
  public void refreshUserStats() { ... }
}
```

> **Design principle**: `group` suits "one write must clear several related cache names", replacing N `@BingCacheEvict` annotations with one. Without a group, each cache name is an independent cache space and you must state which ones to clear. Both follow "neither miss nor over-delete" — `group` achieves it through namespace isolation, multiple annotations through explicit enumeration.

#### Clearing granularity with `group`

`group` provides three tiers of clearing granularity:

| Scenario | Annotation | Effect |
|---|---|---|
| Clear one entry | `@BingCacheEvict(group="user", cacheName="detail", argIndexes={0})` | clears `user:detail(Sg[N:1])` |
| Clear everything under a cache name | `@BingCacheEvict(group="user", cacheName="list", allEntries=true)` | clears every key starting with `user:list(` |
| Clear the whole group | `@BingCacheEvict(group="user", allEntries=true)` | clears every key starting with `user:` (1 SCAN + 1 Pub/Sub) |
| Clear everything | `@BingCacheEvict(allEntries=true)` | clears all caches |

> **`group` cannot stand alone outside `allEntries`**: `@BingCacheEvict(group="user")` without `allEntries=true` and without `cacheName`/`keyPrefix` throws `IllegalStateException`, because no valid key prefix can be derived.

## Cache Key Format

Format: `prefix(arguments)`.

**Prefix priority**: `cacheName` (highest) → `keyPrefix` → default `fully.qualified.ClassName.methodName(paramTypes)`. When `group` is set the key becomes `group:prefix(args)` (e.g. `user:detail(Sg[N:1])`); `group` is an outer namespace prefix and does not affect that priority.

**Argument selection priority**: `argSpel` > `argIndexes` > all arguments.

| Argument type | Element encoding | Single-value example |
|---|---|---|
| null | `null` | `user(Sg[null])` |
| Integer (`Integer` / `Long` / `BigInteger` …) | `N:` + value | `user(Sg[N:42])` |
| String | `S:` + value | `user(Sg[S:42])` |
| Boolean | `B:` + value | `user(Sg[B:true])` |
| Character | `C:` + value | `user(Sg[C:x])` |
| Decimal (`Double` / `Float` / `BigDecimal` …) | `D:` + value | `user(Sg[D:1.5])` |
| Array / `List` | elements serialized recursively | `user(Sg[[N:1, N:2, N:3]])` |
| Custom object | Jackson JSON | `user(Sg[{"id":1,"name":"Alice"}])` |

The outer form depends on how many arguments take part: a **single value** is rendered `Sg[...]`, **two or more** values are rendered `[...]`, and a **no-argument** method yields `prefix()`. This keeps a single collection argument from colliding with several arguments: a lone `List[1,2]` gives `Sg[N:1,N:2]` while two arguments `(1,2)` give `[N:1,N:2]`. Arrays and `List` are equivalent in key form.

Keys are capped at **256 characters**; longer keys have their argument part truncated and a hash suffix appended (`...#` + the first 16 hex characters of the SHA-256 digest) so truncated keys stay unique.

The complete key generation rules, argument encoding table, and edge-case behaviours are documented in [ARCHITECTURE.md](ARCHITECTURE.md#缓存-key-生成规则).

## Cache Modes

The component supports two modes, selected automatically from the classpath and configuration:

| Mode | Condition | Cross-instance invalidation | Use case |
|---|---|---|---|
| **L1 only** (Caffeine) | No Redis dependency, or `bing.cache.redis.enabled=false` | ❌ Current instance only | Single-instance deployment, or low consistency requirements |
| **L1 + L2 two-level** | Redis dependency present and reachable | ✅ Redis Pub/Sub + version reconciliation | Multi-instance deployment needing cross-instance sharing and consistency |

> **Important limitation**: L1-only mode has no Redis Pub/Sub, so `evict()` / `@BingCacheEvict` can only clear the local cache of the **current JVM instance** and cannot notify others. A multi-instance deployment that relies on invalidation staying consistent must enable the L1+L2 mode.

When L1 misses but L2 hits, the L2 value is backfilled into L1 **with the L2 entry's remaining TTL**, so an L1 entry never outlives its L2 counterpart (value and TTL are fetched in a single pipeline round trip). After 3 consecutive Redis failures the component degrades to L1-only mode; recovery requires 3 consecutive successes (anti-flapping protection).

The full data flows, TTL backfill strategy, cross-instance invalidation, version reconciliation, and degradation/recovery mechanics are documented in [ARCHITECTURE.md](ARCHITECTURE.md#缓存架构).

## Configuration

Configure via `application.yml` under the `bing.cache` prefix:

```yaml
bing:
  cache:
    caffeine:
      max-size: 5000                    # Max entries in the shared Caffeine instance (default 5000; used by @BingCache(maxSize=0) entries)
      l1-max-ttl: 0                     # Max L1 entry lifetime in seconds; 0 = unlimited (default 0; auto-falls back to 300 in L1+L2 mode)
    redis:
      enabled: true                     # Enable the L2 Redis cache (default true)
      key-prefix: "bing-cache:"         # Redis key prefix (default bing-cache:)
      channel-name: "bing-cache:invalidation"  # Pub/Sub channel name (default bing-cache:invalidation)
      scan-count: 1000                 # Redis SCAN count hint (default 1000)
      delete-batch-size: 500           # Keys deleted per batch when clearing (default 500)
      use-unlink: true                 # Prefer async UNLINK, fall back to DEL (default true)
      failure-log-interval: 30         # Throttle interval for repeated failure logs while degraded (default 30)
    reconciliation:
      enabled: true                     # Enable version reconciliation (default true)
      interval: 30                      # Reconciliation interval in seconds (default 30)
```

| Property | Default | Description |
|---|---|---|
| `bing.cache.caffeine.max-size` | `5000` | Max entries in the shared Caffeine instance. Applies only to caches with no declared `maxSize` (or `maxSize=0`); when an annotation declares `maxSize > 0` that prefix owns a separate Caffeine instance sized by the annotation |
| `bing.cache.caffeine.l1-max-ttl` | `0` | Max L1 entry lifetime in seconds; `0` means unlimited. Once set, no L1 entry outlives this value — the backstop for lost Pub/Sub messages or an unavailable Redis. **In L1+L2 mode, leaving it at `0` makes the component fall back to 300 seconds** (because lost Pub/Sub for single-key evict cannot be compensated by reconciliation); in L1-only mode `0` truly means unlimited |
| `bing.cache.redis.enabled` | `true` | Enable the L2 Redis cache. Only effective when the Redis dependency is on the classpath and reachable; cross-instance invalidation for `evict()` / `@BingCacheEvict` relies on Redis Pub/Sub in this mode |
| `bing.cache.redis.key-prefix` | `bing-cache:` | Prefix for cache keys in Redis, for namespace isolation |
| `bing.cache.redis.channel-name` | `bing-cache:invalidation` | Pub/Sub channel for invalidation notifications; only used when the L2 Redis cache is enabled |
| `bing.cache.redis.scan-count` | `1000` | Redis SCAN count hint used when `clear()` / `clearByPrefix()` scan keys |
| `bing.cache.redis.delete-batch-size` | `500` | Keys deleted per batch when clearing Redis, avoiding one huge delete |
| `bing.cache.redis.use-unlink` | `true` | Prefer async `UNLINK` when clearing Redis keys; on UNLINK failure the current batch and all later batches in the same call fall back to `DEL`; a DEL failure aborts the clear and records a degradation (same path as L1 degradation) |
| `bing.cache.redis.failure-log-interval` | `30` | Minimum interval in seconds between repeated failure logs while Redis is degraded |
| `bing.cache.reconciliation.enabled` | `true` | Enable version reconciliation to compensate for lost Pub/Sub messages |
| `bing.cache.reconciliation.interval` | `30` | Reconciliation interval in seconds, valid range 1–86400 (24 hours); startup validation fails outside this range |

### Enabling the L2 Redis cache

Make sure the project depends on `spring-boot-starter-data-redis` and configure the Redis connection:

```xml
<!-- consumer project pom.xml -->
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

`bing.cache.redis.enabled` defaults to `true`, so L1+L2 mode turns on automatically once Redis is reachable.

### Disabling the L2 Redis cache

```yaml
bing:
  cache:
    redis:
      enabled: false
```

The component falls back to L1-only mode.

### Projects without Redis

Without `spring-boot-starter-data-redis` on the classpath the component runs in L1-only mode with no extra configuration. Its scope is `provided`, so consumers add it only if they want L2.

## Manual Cache Management

Inject the `CacheManager` interface to manage caches by hand:

```java
@Resource
private CacheManager cacheManager;

// Clear a specific key
cacheManager.evict("user(Sg[N:1])");

// Clear every entry under a cache name (matches the exact "user(" prefix, so "userDetail" is untouched)
cacheManager.clearByPrefix("user");

// Clear every entry under a group (matches the whole "user:" namespace)
cacheManager.clearByGroup("user");

// Clear everything
cacheManager.clear();
```

> When evicting manually, the key must match exactly what `CacheKeyGenerator` produces. Prefer the `@BingCacheEvict` annotation.

### Exposing a manual clear endpoint

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

## Logging and Debugging

Enable DEBUG logging to see cache hits:

```yaml
logging:
  level:
    com.bing.cache: DEBUG
```

Logs come in two layers: the **aspect layer** (`CacheAspect` / `CacheEvictAspect`) logs in both L1-only and L1+L2 mode; the **two-level cache layer** (`CompositeCacheManager` / `RedisCacheManager`) logs additionally in L1+L2 mode.

Aspect layer (both modes):

```
DEBUG Cache hit: user(Sg[N:1])                     # cache hit
DEBUG Cache hit (null sentinel): user(Sg[N:999])   # hit the null placeholder (cacheNullValue=true)
DEBUG Cache miss: user(Sg[N:1])                    # cache miss
DEBUG Cache put: user(Sg[N:1])                     # cache write
DEBUG Cache put (null value): user(Sg[N:999])      # null value cached
DEBUG Cache skip (null result): user(Sg[N:999])    # null result skipped
DEBUG Cache evict: user(Sg[N:1])                   # single-key invalidation
DEBUG Cache clear by prefix: user                  # clear by prefix (allEntries + cacheName/keyPrefix)
DEBUG Cache clear by group: admin                  # clear by group (allEntries + group only)
DEBUG Cache clear all entries                      # global clear (allEntries, no cacheName/keyPrefix/group)
```

Two-level cache layer (L1+L2 only):

```
DEBUG L1 cache hit: user(Sg[N:1])                     # L1 hit
DEBUG L2 cache hit, backfilling L1: user(Sg[N:1])     # L2 hit, backfilling L1
DEBUG L1+L2 cache miss: user(Sg[N:1])                 # both L1 and L2 missed
DEBUG Redis cache hit: bing-cache:user(Sg[N:1])       # Redis hit
DEBUG Cache put (L1+L2): user(Sg[N:1])                # written to both levels
DEBUG Cache evict (L2+L1+pub): user(Sg[N:1])          # L2 + L1 cleared and Pub/Sub published
DEBUG Cache clear by prefix (L2+L1+pub): user         # clear by prefix (L2 + L1 + Pub/Sub)
DEBUG Cache clear (L2+L1+pub)                         # global clear (L2 + L1 + Pub/Sub)
```

Redis degradation and recovery:

```
WARN  Bing Cache: Redis L2 cache has failed 3 consecutive times, degraded to L1-only mode. Check Redis connectivity.  # degraded after 3 failures
WARN  Bing Cache: Redis L2 cache still degraded. ...                                                                  # summary log, throttled by failure-log-interval
INFO  Bing Cache: Redis L2 cache has recovered from degradation                                                        # recovered after 3 successes
```

## Caveats

1. **Self-invocation does not work**: an internal call within the same class bypasses the AOP proxy, so cache annotations have no effect. Call through a Spring-injected bean.

2. **Cross-instance invalidation requires L1+L2 mode**: it is implemented with Redis Pub/Sub. Without a Redis dependency, with Redis unreachable, or with `bing.cache.redis.enabled=false`, the component runs L1-only and `evict()` / `@BingCacheEvict` clear only the current JVM instance.

3. **Pub/Sub delivery is not guaranteed**: invalidation messages are broadcast fire-and-forget. In the worst case (e.g. network jitter) other instances miss the notification and read stale data for a short while. **Note: version reconciliation only compensates lost Pub/Sub for `clear()`, `clearByPrefix()` and `clearByGroup()`; a lost single-key `evict()` cannot be compensated** (see [reconciliation scope limits](ARCHITECTURE.md#对账范围限制重要)). Set `l1-max-ttl` as a backstop in production.

4. **Suitable workloads**: this component targets read-heavy, eventually-consistent data (dictionaries, user profiles, configuration). It is not a fit for frequently updated data that requires strong consistency.

5. **Multi-instance deployment**: each instance has an independent L1. `@BingCacheEvict` only notifies other instances once L1+L2 mode is enabled, and delivery has millisecond-level latency. For strong consistency, query the database directly.

6. **Manual key consistency**: a manual `evict()` key must match the generated one exactly; copy it from the DEBUG logs. Prefer `@BingCacheEvict` over manual calls.

7. **Redis is optional**: `spring-boot-starter-data-redis` has `provided` scope, so consumers add it when needed. Without it the component runs L1-only.

8. **Scope of `allEntries`**: `@BingCacheEvict(allEntries = true)` with a `cacheName` or `keyPrefix` clears only that prefix; with only a `group` it clears that group; with neither it clears everything.

9. **`clearByPrefix` matches exactly**: internally it matches keys starting with `prefix + "("`, so `clearByPrefix("user")` never clears `userDetail`. Glob metacharacters (`*`, `?`, …) in `prefix` are treated literally — Redis SCAN results are re-filtered with `startsWith`.

10. **A WARN when `@BingCacheEvict` sets neither cacheName nor keyPrefix**: the default prefix then becomes the evicting method's name (e.g. `updateUser`) while the `@BingCache` default prefix is its own method name (e.g. `getUserById`), so the keys do not match and the eviction silently misses. The component logs:

    ```
    WARN @BingCacheEvict on method 'updateUser' has no cacheName or keyPrefix set.
    The default prefix (this method name) may not match @BingCache's method name,
    causing eviction to silently miss the cached key.
    Consider setting cacheName to match @BingCache.
    ```

    Always give `@BingCacheEvict` a `cacheName` that matches its `@BingCache`.

## Compatibility

| Item | Supported | Notes |
|---|---|---|
| JDK | Java 17+ | Artifacts are compiled with `--release 17` and run on JDK 17 and above |
| Spring Boot | 3.x | Current test/dependency baseline is Spring Boot 3.5.13; targets Spring Boot 3.x / Spring Framework 6.x / Jakarta |
| Spring Boot 2.x | Not supported | Spring Boot 2.x is still on `javax.*` and does not match the Spring Boot 3 / Jakarta dependencies used here |

Other Spring Boot 3.x baselines can be verified locally via Maven profiles:

```bash
mvn clean test -Pboot-3.2
mvn clean test -Pboot-3.3
mvn clean test -Pboot-3.5
```

## Tech Stack

- Java 17+
- Spring Boot 3.x (current test/dependency baseline: 3.5.13)
- Caffeine (version managed by the Spring Boot BOM)
- Spring Data Redis (provided scope, supplied by the consumer)
- AspectJ (version managed by the Spring Boot BOM)
- Jackson (key generation + Redis serialization)
- JUnit 5 + Mockito (unit tests)
- Testcontainers (integration tests)

## Repository Layout and Build

A Maven multi-module project:

```text
bing-cache/
├── pom.xml              # Parent POM / reactor aggregator: versions, dependencies, plugins
├── bing-cache-core/     # Starter sources and unit tests; the published artifactId is still bing-cache
│   ├── src/main/java/com/bing/cache/
│   └── src/test/java/com/bing/cache/
└── bing-cache-test/     # Integration test module, depends on bing-cache from this reactor
    ├── src/main/java/com/example/demo/
    └── src/test/java/com/example/demo/
```

The published coordinates stay `cn.com.bingbing:bing-cache:1.1-SNAPSHOT`. Business projects keep depending on that coordinate; nobody needs to depend on the `bing-cache-core` directory name.

Common commands:

```bash
# Full build
mvn clean verify

# Install the parent POM and all modules into the local Maven repository
mvn clean install

# Recommended: install only the parent + core that consumers need
mvn clean install -pl bing-cache-core -am

# Verify the core module only
mvn -pl bing-cache-core -am verify

# Build the integration test module, building its core dependency automatically
mvn -pl bing-cache-test -am verify
```
