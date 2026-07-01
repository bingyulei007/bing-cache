# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Bing Cache 是一个 Maven 多模块仓库，提供基于 Spring AOP 的方法级缓存 Spring Boot Starter，通过注解透明接入缓存能力，采用两级架构：L1 本地缓存（Caffeine）和 L2 分布式缓存（Redis）。核心包名：`com.bing.cache`。

模块布局：

- `bing-cache-core/` — 核心 starter library，发布 artifact 仍为 `cn.com.bingbing:bing-cache`。
- `bing-cache-test/` — 集成测试模块，依赖 reactor 构建出的 core artifact。

## Build & Test Commands

```bash
# 全量构建（编译 + 测试）
mvn clean verify

# 安装多模块 artifact 到本地仓库，供下游或独立模块复用
mvn clean install

# 编译所有模块（跳过测试）
mvn compile

# 运行 core 模块全部测试
mvn test -pl bing-cache-core

# 运行 core 模块单个测试类
mvn test -pl bing-cache-core -Dtest=CacheAspectTest

# 运行 core 模块单个测试方法
mvn test -pl bing-cache-core -Dtest=CacheAspectTest#shouldReturnCachedValue

# 运行 reactor 集成测试模块（自动构建依赖模块）
mvn verify -pl bing-cache-test -am

# 运行 core 模块内 Testcontainers 集成测试（需要 Docker）
mvn test -pl bing-cache-core -Dtest=CompositeCacheManagerIntegrationTest
```

**JDK requirement**: Must use JDK 17. Newer JDKs are okay when compiling with release 17. System default JDK 8 will cause Caffeine 3.x class loading failures.

## Architecture

### Core flow: Annotation → AOP → CacheManager → L1/L2

```
@BingCache / @BingCacheEvict  (with optional `group` for namespace layering)
       ↓
CacheAspect / CacheEvictAspect  (aspect/)
       ↓
CacheKeyGenerator               (util/)  — key format: `group:prefix(args)` when group set
       ↓
CacheManager interface          (cache/)  — get/put/evict/clear/clearByPrefix/clearByGroup
  ├── CaffeineCacheManager      — L1 only mode
  └── CompositeCacheManager     — L1+L2 mode
        ├── CaffeineCacheManager (L1)
        ├── RedisCacheManager    (L2)
        ├── CacheInvalidationPublisher → Redis Pub/Sub (EVICT/CLEAR/CLEAR_PREFIX/CLEAR_GROUP)
        ├── CacheVersionStore → Redis version keys (per-cacheName / __all__ / __group__:{group})
        └── CacheReconciliationService → periodic version check
```

### Key packages

以下包目录位于 `bing-cache-core/src/main/java/com/bing/cache/` 下：

- `annotation/` — `@BingCache`, `@BingCacheEvict` annotations
- `aspect/` — `CacheAspect` (read), `CacheEvictAspect` (evict), `BingCacheNullValue` (package-private null sentinel)
- `cache/` — `CacheManager` interface and all implementations (Caffeine, Redis, Composite), Pub/Sub messaging (`CacheInvalidationMessage`, `CacheInvalidationPublisher`, `RedisCacheInvalidationPublisher`, `CacheInvalidationListener`), version reconciliation (`CacheVersionStore`, `CacheReconciliationService`)
- `config/` — `BingCacheAutoConfiguration` (conditional bean wiring), `BingCacheProperties` (`bing.cache.*`)
- `util/` — `CacheKeyGenerator` (deterministic key generation via Jackson serialization)

### Key components

#### CaffeineCacheManager

- **Single Cache + per-entry Expiry**: Uses one Caffeine Cache instance with `CacheEntry` record carrying per-entry `expireNanos`. `CacheEntryExpiry` implements Caffeine's `Expiry` interface for per-entry expiration. This replaces an older multi-Cache-by-TTL design that could create unbounded Cache instances under dynamic TTLs.
- **L1 max TTL**: `l1MaxTtlSeconds` caps all L1 entry lifetimes. Applied in `put()` via `min(expireSeconds, l1MaxTtlSeconds)`. When `expireSeconds <= 0` (no expiry) but `l1MaxTtlSeconds > 0`, uses `l1MaxTtlSeconds` instead.
- **`keys()` method**: Returns `cache.asMap().keySet()` — a **live view** of the underlying Caffeine cache. Iterating it while another thread writes may throw `ConcurrentModificationException`; callers must not `remove()`/`clear()` through it. **Not used in production reconciliation path** (reconciliation goes through `clearByPrefix`'s internal `removeIf`); currently only referenced in `CaffeineCacheManagerTest` assertions. Consider returning a snapshot copy (`new HashSet<>(cache.asMap().keySet())`) if any new caller needs iteration safety.
- **`clearByPrefix(prefix)`**: O(n) scan over all keys (`removeIf(key -> key.startsWith(prefix + "("))`). Appends `(` for exact cacheName matching to avoid one cacheName being a prefix of another (e.g. `clearByPrefix("user")` should not evict `userDetail` keys).
- **`clearByGroup(group)`**: O(n) scan matching keys starting with `group + ":"`.

#### CacheVersionStore

- Redis-backed version counter per cacheName. Key format: `{keyPrefix}__version__:{cacheName}`.
- Global version key: `{keyPrefix}__version__:__all__`.
- Group version key: `{keyPrefix}__version__:__group__:{group}` — namespace-separated by `__group__:`, consistent with `__all__` naming style.
- `incrementVersion(cacheName)` / `incrementAllVersion()` / `incrementGroupVersion(group)` — called by CompositeCacheManager on `clearByPrefix(prefix)` / `clear()` / `clearByGroup(group)` respectively. Single-key `evict()` does NOT increment (see Evict flow below).
- `getVersion(cacheName)` / `getGroupVersion(group)` / `getActiveCacheNames()` / `getActiveGroups()` — called by CacheReconciliationService during periodic checks.
- **`getActiveCacheNames()` filters out internal reserved prefixes**: SCAN over `versionKeyPrefix + "*"` would otherwise return `__all__` and `__group__:{group}` strings as cacheNames, polluting reconciliation. The filter `ALL_VERSION_SUFFIX.equals(name) || name.startsWith(GROUP_VERSION_PREFIX)` skips them. `__all__` and `__group__:` are reserved — business code must not use them as cacheName.
- Registered as a single shared bean in `BingCacheAutoConfiguration` (consumed by both `CompositeCacheManager` and `CacheReconciliationService` via `ObjectProvider`).

#### CacheReconciliationService

- Implements `SmartLifecycle`, starts a single-thread `ScheduledExecutorService`.
- `reconcile()`: checks global version first; if changed, clears all L1. Then checks each active cacheName's version; if changed, clears L1 by prefix. Finally checks each active group's version; if changed, clears L1 by group.
- First reconciliation runs immediately (initialDelay=0) to detect pre-existing version drift.
- Maintains `lastKnownVersions` map, `lastKnownAllVersion`, and `lastKnownGroupVersions` map to detect changes.
- `refreshAllKnownVersions()` (called on global version change): refreshes both cacheName and group version state, retaining only currently-active names to prevent unbounded map growth.
- Per-cycle `retainAll`: each reconcile cycle (when SCAN succeeds) also converges `lastKnownVersions` / `lastKnownGroupVersions` to the currently-active set, cleaning up stale entries between two global `clear()` events. Zero extra Redis cost — reuses the SCAN result already fetched for version checks. Skipped when `getActiveCacheNames()` / `getActiveGroups()` returns `Optional.empty()` (SCAN unavailable) to preserve state.
- **`initialized` flag** (volatile boolean, defaults `false`): flipped to `true` only after the first reconcile cycle completes both cacheName and group SCANs successfully. While `false`, a "first-seen" cacheName/group (Redis has the version key but local map doesn't) is recorded without clearing L1 — this avoids mis-clearing L1 for pre-existing version keys that existed before this instance started. After `initialized=true`, a newly-appearing cacheName/group is treated as "another instance cleared it since our last cycle" and L1 is cleared by prefix/group to stay consistent. If a mid-cycle exception prevents the full SCAN from completing, `initialized` stays `false` for the next cycle — conservative degradation that may delay handling of new version keys by one cycle.
- **Local version baseline storage**: the three fields above are pure JVM memory (not persisted, not in L1). Each instance maintains its own baseline independently. On instance restart the baseline is lost and rebuilt via the `initialized=false` first-cycle logic (see above). Redis stores "current version"; local stores "last-known version"; reconcile diffs them to decide L1 clearing. Correspondence: `lastKnownAllVersion` ↔ `{keyPrefix}__version__:__all__`; `lastKnownVersions.get(name)` ↔ `{keyPrefix}__version__:{name}`; `lastKnownGroupVersions.get(group)` ↔ `{keyPrefix}__version__:__group__:{group}`.

#### CompositeCacheManager

- **Backfill race fix**: `backfillL1()` checks `remainingTtl`: >0 → use it; -1 → L1 no expiry; -2/0 → skip backfill + warn log.
- **Version increment**: `clear()` calls `incrementAllVersion()`, `clearByPrefix(prefix)` calls `incrementVersion(prefix)`, `clearByGroup(group)` calls `incrementGroupVersion(group)`. All three guarded by `versionStore != null`. `evict(key)` does NOT increment version.
- **`clearByGroup(group)` ordering**: L2 → L1 → publish → version (same as `clearByPrefix`), minimizing TOCTOU window.
- **Redis recovery callback** (`RedisCacheManager.setRecoveryCallback()`, wired in auto-config): conditional — when reconciliation is enabled, the callback only logs and lets the reconciliation service clear L1 incrementally at the next cycle (avoids stampede); when reconciliation is disabled, the callback calls `l1CacheManager::clear` immediately.

### Auto-configuration conditions

- `CompositeCacheManager` activates when `RedisConnectionFactory` bean exists AND `bing.cache.redis.enabled=true` (default)
- `CaffeineCacheManager` activates as fallback via `@ConditionalOnMissingBean(CacheManager.class)`
- `CacheReconciliationService` activates when Redis is available AND `bing.cache.reconciliation.enabled=true` (default)
- Registered via `AutoConfiguration.imports` (Spring Boot 3.x). `spring.factories` 已删除（Spring Boot 3.x 不再读取该文件用于自动配置）。

### Key design decisions

- **Group namespace layering**: `@BingCache` / `@BingCacheEvict` support an optional `group` attribute. When set, the cache key is prefixed as `group:prefix(args)`, creating a three-tier clear granularity: `group > cacheName > specific key`. `@BingCacheEvict(group="user", allEntries=true)` (without cacheName/keyPrefix) triggers `clearByGroup(group)` — one Redis SCAN + one Pub/Sub message, avoiding N cacheName-level scans. `@BingCacheEvict(group="user", cacheName="list", allEntries=true)` still goes through `clearByPrefix("user:list")`.
- **Group-alone validation**: `group` without `allEntries=true` and without `cacheName`/`keyPrefix` is a hard error — `CacheKeyGenerator.generate()` throws `IllegalStateException` because no valid key prefix can be derived (would produce `:args`). This is a hard-validation, intentionally different from the soft WARN style of `argSpel`/`argIndexes` conflict checks (where a key can still be generated).
- **CacheName colon restriction**: `cacheName`/`keyPrefix` should NOT contain `:` when `group` is used. `group` uses `:` as the namespace separator, so a `cacheName = "user:detail"` collides with `group = "user"` + `cacheName = "detail"` (both produce `user:detail(args)` keys). `clearByGroup("user")` would then unintentionally clear caches that merely have a colon-style cacheName. README documents this constraint; use camelCase cacheName (e.g. `userDetail`) or the `group` attribute for layering.
- **BingCacheNullValue sentinel**: Caffeine cannot store null. `BingCacheNullValue` (package-private, implements `NullValueSentinel`) is used as a placeholder. It is only stored in L1, never in L2 (Jackson can't deserialize the package-private class). `CompositeCacheManager` detects it via `instanceof NullValueSentinel` (类型安全，不依赖类名字符串)。**限制**：`cacheNullValue=true` 只防本实例穿透；多实例下其他实例查询不存在的 id 仍会回源（NullValue 不写 L2）。
- **L1 backfill carries L2 remaining TTL**: When L2 hits but L1 misses, `RedisCacheManager.getRemainingTtl()` fetches the remaining expiry so L1 entries don't outlive their L2 counterparts. Race condition: if TTL returns -2/0, backfill is skipped.
- **Evict order**: L2 cleared before L1 to minimize TOCTOU window.
- **Cache key format**: `prefix(args)` — prefix priority: `cacheName` > `keyPrefix` > `fullyQualifiedClassName.methodName(paramTypes)`. When `group` is set, the full prefix becomes `group:prefix`. Max 256 chars; overflow gets truncated with SHA-256 hash suffix (first 16 hex chars). Argument serialization uses type-prefixed elements (`N:` integers, `S:` strings, `B:` boolean, `C:` char, `D:` decimal) wrapped by selection-count marker: single-value selection (1 arg) yields `Sg[...]` (e.g. `user(Sg[N:1])`); multi-value selection (≥2 args) yields `[...]` (e.g. `user([N:1,N:2])`); no-arg methods yield `prefix()`. The `Sg` prefix (single) distinguishes single-value collection args from multi-args, preventing collision (single `List[1,2]` → `Sg[N:1,N:2]` vs two args `(1,2)` → `[N:1,N:2]`). Three selection modes produce identical keys: `argSpel` single-value, `argIndexes={0}`, default single-arg all yield `Sg[...]`; `argSpel` `{#a,#b}` (SpEL list literal, detected by `{`...`}` syntax), `argIndexes={0,1}`, default multi-arg all yield `[...]`. List and arrays are equivalent (`[N:1,N:2,N:3]`). Integer types normalized (`Integer`/`Long`/`BigInteger` → `N:1`). Custom objects use Jackson; failures throw `IllegalStateException` rather than degrading to `hashCode()`.
- **Single Caffeine Cache**: Per-entry `Expiry` replaces the old multi-Cache-by-TTL design, preventing dynamic TTL from creating unbounded Cache instances.
- **Degradation threshold**: 3 consecutive Redis failures mark L2 as degraded; L2 operations silently fail and the cache falls back to L1-only mode. Degradation emits a single WARN, subsequent failures within `failureLogIntervalSeconds` are throttled to one summary WARN (no stack trace) per interval to prevent log floods.
- **Flapping-protected recovery**: After degradation, recovery requires 3 consecutive successful operations (matches the degradation threshold) before triggering the recovery callback. Any failure resets the success counter. This prevents rapid Redis flapping from repeatedly clearing L1 (which would cause cache stampede). `recordSuccess` uses a fast-path (no lock) in the non-degraded state.
- **Bulk deletion via SCAN + UNLINK/DEL**: `clear()`, `clearByPrefix()`, and `clearByGroup()` use `SCAN` with configurable `scanCount`, batching keys into `deleteBatchSize` chunks for `UNLINK` (async, Redis 4.0+) with automatic per-batch fallback to `DEL` on UNLINK failure. Once UNLINK fails, all subsequent batches in the same call use DEL without retry. DEL exceptions propagate to the outer `try` block and trigger `recordFailure()` rather than being swallowed — this prevents degraded-write scenarios (e.g., read-OK/write-fail during master-slave failover) from falsely flipping the degraded state back to normal.
- **Literal-prefix filter for L1/L2 consistency**: `clearByPrefix()` / `clearByGroup()` in `RedisCacheManager` re-filters SCAN results with `String.startsWith(literalPrefix)` in Java before deletion. This ensures identical semantics with `CaffeineCacheManager` (which uses `startsWith`) and prevents Redis glob meta-characters (`*`, `?`, `[`, `]`) in the prefix from unintentionally expanding the match set. The 5-10% extra CPU cost (O(n) `new String` + `startsWith` on scanned keys) is acceptable and does not add Redis load.
- **Rolling-upgrade tolerance for Pub/Sub `Type` enum**: `CacheInvalidationMessage.OBJECT_MAPPER` enables `READ_UNKNOWN_ENUM_VALUES_AS_NULL`. When a newer instance publishes a `Type` value unknown to an older instance (e.g. `CLEAR_GROUP` during upgrade), the old instance deserializes `type` to `null` and hits the `type == null` → WARN branch (clean, no stack trace), NOT an `InvalidFormatException` → ERROR + stack trace. L1 is not cleared; `l1-max-ttl` provides the eventual-consistency backstop. This is a defensive improvement that benefits any future `Type` addition, not just `CLEAR_GROUP`.

### Data flows

**Write flow** (CacheAspect):
```
@BingCache → generate key → cacheManager.get(key)?
  ├─ hit + not BingCacheNullValue → return cached value
  ├─ hit + BingCacheNullValue → return null
  └─ miss → proceed() → result != null? → cacheManager.put(key, result, expireTime)
                                → result == null + cacheNullValue → put(key, BingCacheNullValue.INSTANCE, expireTime)
                                → result == null + !cacheNullValue → skip
```

**Read flow** (CompositeCacheManager.get):
```
L1.get(key)?
  ├─ hit → return
  └─ miss → L2.get(key)?
              ├─ hit → backfillL1(key, value) → return
              │         ├─ remainingTtl > 0 → L1.put(key, value, remainingTtl)
              │         ├─ remainingTtl == -1 → L1.put(key, value, 0)
              │         └─ remainingTtl == -2/0 → skip (warn log)
              └─ miss → return null
```

**Evict flow** (CompositeCacheManager.evict):
```
L2.evict(key) → L1.evict(key) → publisher.publishEvict(key)
# Note: single-key evict does NOT increment version — versionStore is not called.
# Reason: per-key version keys would grow unbounded in Redis. Cross-instance
# invalidation for single-key evict relies entirely on Pub/Sub + l1-max-ttl backstop.
```

**clearByPrefix flow** (CompositeCacheManager.clearByPrefix, triggered by `@BingCacheEvict(cacheName=..., allEntries=true)`):
```
L2.clearByPrefix(prefix) → L1.clearByPrefix(prefix) → publisher.publishClearByPrefix(prefix) → incrementVersion(prefix)
# prefix is "group:cacheName" when group is set, otherwise "cacheName".
```

**clearByGroup flow** (CompositeCacheManager.clearByGroup, triggered by `@BingCacheEvict(group=..., allEntries=true)` without cacheName/keyPrefix):
```
L2.clearByGroup(group) → L1.clearByGroup(group) → publisher.publishClearByGroup(group) → incrementGroupVersion(group)
# Only clear() / clearByPrefix(prefix) / clearByGroup(group) increment version numbers.
# Single-key evict() does NOT — its cross-instance invalidation relies on Pub/Sub + l1-max-ttl backstop.
```

**CacheEvictAspect.doEvict branch logic** (when `allEntries=true`):
- `group` set + no cacheName/keyPrefix → `clearByGroup(group)` (clears entire group namespace)
- `cacheName` or `keyPrefix` set (optionally with `group`) → `clearByPrefix(resolvedPrefix)` where resolvedPrefix = `group:cacheName` if group set, else `cacheName`
- neither set → `clear()` (global clear)

## Conventions

- **No Lombok**: All classes use hand-written getters/setters/loggers. Logger field name is `LOG`.
- **Chinese comments and Javadoc**: The codebase uses Chinese for documentation. Match this style for consistency.
- **Git commit format**: `[bing-cache] description` — Change-Id auto-generated by Gerrit hook, do not add manually. Jira number optional (none of the recent commits include one; add only when the change is tied to a specific ticket).
- **MapStruct**: Listed as annotation processor but not currently used in source code.

## Configuration Properties

Prefix: `bing.cache`

| Property | Default | Description |
|----------|---------|-------------|
| `caffeine.max-size` | 1000 | Caffeine Cache 的最大条目数 |
| `caffeine.l1-max-ttl` | 0 | L1 最大存活秒数，0 表示不限制。L1+L2 模式下若为 0，自动兜底为 300s（`DEFAULT_L1_MAX_TTL_SECONDS`） |
| `redis.enabled` | true | Enable L2 Redis cache (requires Redis on classpath) |
| `redis.key-prefix` | `bing-cache:` | Redis key namespace prefix |
| `redis.channel-name` | `bing-cache:invalidation` | Pub/Sub channel for cross-instance invalidation |
| `redis.scan-count` | 1000 | SCAN command COUNT hint for `clear()` / `clearByPrefix()` key scanning |
| `redis.delete-batch-size` | 500 | Number of keys per UNLINK/DEL batch during prefix clearing |
| `redis.use-unlink` | true | Prefer UNLINK (async) over DEL; auto-fall-back to DEL per batch on UNLINK failure |
| `redis.failure-log-interval` | 30 | Minimum interval (seconds) between repeated failure logs during Redis degradation |
| `reconciliation.enabled` | true | Enable version reconciliation for Pub/Sub loss compensation |
| `reconciliation.interval` | 30 | Reconciliation check interval in seconds |

`BingCacheProperties` is annotated with `@Validated` and uses Bean Validation (`@Min`/`@Max` constraints on `maxSize`, `l1MaxTtl`, `interval`, `scanCount`, `deleteBatchSize`, `failureLogInterval`). Invalid values fail binding at startup rather than causing runtime errors (e.g., `interval=0` would otherwise make `scheduleAtFixedRate` throw).

## Test Structure

- Core 单元测试位于 `bing-cache-core/src/test/java/.../` — Mockito for mocking Redis, JUnit 5
- Core 集成测试：`CompositeCacheManagerIntegrationTest`, `ReconciliationIntegrationTest` — use Testcontainers (redis:7-alpine), requires Docker
- Reactor 集成测试模块：`bing-cache-test/`，用于验证依赖 reactor 构建 core artifact 的集成场景。`MultiInstanceCacheTest` 默认 `@Disabled`，需手动启动多实例后运行
- 测试数量以 Maven 测试输出为准，避免文档中的固定计数随用例增减而过期

### Running `bing-cache-test` locally

`bing-cache-test/src/main/resources/application.yml` 配置了远程 Redis（`cmac-mini:6379` 带密码）。当该主机不可达时，用以下本地替代方案：

```bash
# 方式 A：使用仓库自带的 Podman 脚本（启动临时 Redis，跑完测试自动清理）
# 前置：podman machine 已启动 (podman machine start)
./bing-cache-test/scripts/run-tests-with-podman-redis.sh                    # 跑全部启用的测试类
./bing-cache-test/scripts/run-tests-with-podman-redis.sh -Dtest=BingCacheTest   # 单个测试类

# 方式 B：手动启动 Redis 并通过系统属性覆盖
# Windows 下 podman 默认把端口绑到 WSL VM 的 0.0.0.0 而非 Windows 127.0.0.1，需显式绑 IPv4 loopback：
podman run -d --name bing-cache-redis -p 127.0.0.1:6379:6379 docker.io/library/redis:7-alpine
mvn test -pl bing-cache-test -DargLine="-Dspring.data.redis.host=127.0.0.1 -Dspring.data.redis.password="
```

**Podman + Windows 注意**：Podman 在 WSL VM 里跑 Redis。默认 `podman run -p 6379:6379` 绑定到 VM 的 `0.0.0.0`（通过 WSL VM IP 可达），但不会绑到 Windows `127.0.0.1`。Lettuce/Java 解析 `localhost` 通常走 IPv4 → 连接被拒。两种解法：(a) 用 `-p 127.0.0.1:6379:6379` 强制绑 IPv4 loopback（取决于 Podman machine 网络模式），或 (b) 用自带脚本通过 `wsl -d podman-machine-default` 解析 VM IP。

### Multi-instance tests

`MultiInstanceCacheTest`（默认 `@Disabled`）验证跨实例 Pub/Sub + 对账失效。运行方式：

```bash
# 启动 3 个 Spring Boot 实例（端口 8081-8083）共享一个 Redis
./bing-cache-test/scripts/start-instances.sh 3 8081
# 然后去掉 @Disabled，对任意一个实例跑测试
```
