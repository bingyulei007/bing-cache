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
@BingCache / @BingCacheEvict
       ↓
CacheAspect / CacheEvictAspect  (aspect/)
       ↓
CacheKeyGenerator               (util/)
       ↓
CacheManager interface          (cache/)
  ├── CaffeineCacheManager      — L1 only mode
  └── CompositeCacheManager     — L1+L2 mode
        ├── CaffeineCacheManager (L1)
        ├── RedisCacheManager    (L2)
        ├── CacheInvalidationPublisher → Redis Pub/Sub
        ├── CacheVersionStore → Redis version keys
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

- **Single Cache + per-entry Expiry**: Uses one Caffeine Cache instance with `CacheEntry` record carrying per-entry `expireNanos`. `CacheEntryExpiry` implements Caffeine's `Expiry` interface for per-entry expiration.
- **L1 max TTL**: `l1MaxTtlSeconds` caps all L1 entry lifetimes. Applied in `put()` via `min(expireSeconds, l1MaxTtlSeconds)`. When `expireSeconds <= 0` (no expiry) but `l1MaxTtlSeconds > 0`, uses `l1MaxTtlSeconds` instead.
- **`keys()` method**: Exposes `cache.asMap().keySet()` for reconciliation prefix checking.

#### CacheVersionStore

- Redis-backed version counter per cacheName. Key format: `{keyPrefix}version:{cacheName}`.
- Global version key: `{keyPrefix}version:__all__`.
- `incrementVersion(cacheName)` / `incrementAllVersion()` — called by CompositeCacheManager on clear/clearByPrefix only; single-key evict relies on Pub/Sub + l1-max-ttl backstop and does not increment version.
- `getVersion(cacheName)` / `getActiveCacheNames()` — called by CacheReconciliationService during periodic checks.

#### CacheReconciliationService

- Implements `SmartLifecycle`, starts a single-thread `ScheduledExecutorService`.
- `reconcile()`: checks global version first; if changed, clears all L1. Then checks each active cacheName's version; if changed, clears L1 by prefix.
- First reconciliation runs immediately (initialDelay=0) to detect pre-existing version drift.
- Maintains `lastKnownVersions` map and `lastKnownAllVersion` to detect changes.

#### CompositeCacheManager

- **Backfill race fix**: `backfillL1()` checks `remainingTtl`: >0 → use it; -1 → L1 no expiry; -2/0 → skip backfill + warn log.
- **Version increment**: Only `clear()` calls `incrementAllVersion()` and `clearByPrefix(prefix)` calls `incrementVersion(prefix)`. Single-key `evict()` relies on Pub/Sub + l1-max-ttl backstop and does not increment version. All guarded by `versionStore != null`.
- **Redis recovery callback**: `RedisCacheManager.setRecoveryCallback()` is wired in auto-config to `l1CacheManager::clear`, ensuring L1 dirty data is cleared when Redis recovers from degradation.

### Auto-configuration conditions

- `CompositeCacheManager` activates when `RedisConnectionFactory` bean exists AND `bing.cache.redis.enabled=true` (default)
- `CaffeineCacheManager` activates as fallback via `@ConditionalOnMissingBean(CacheManager.class)`
- `CacheReconciliationService` activates when Redis is available AND `bing.cache.reconciliation.enabled=true` (default)
- Registered via `AutoConfiguration.imports` (Spring Boot 3.x). `spring.factories` 已删除（Spring Boot 3.x 不再读取该文件用于自动配置）。

### Key design decisions

- **BingCacheNullValue sentinel**: Caffeine cannot store null. `BingCacheNullValue` (package-private) is used as a placeholder. It is only stored in L1, never in L2 (Jackson can't deserialize it). `CompositeCacheManager` detects it via `instanceof NullValueSentinel` (类型安全，不依赖类名字符串)。**限制**：`cacheNullValue=true` 只防本实例穿透；多实例下其他实例查询不存在的 id 仍会回源（NullValue 不写 L2）。
- **L1 backfill carries L2 remaining TTL**: When L2 hits but L1 misses, `RedisCacheManager.getRemainingTtl()` fetches the remaining expiry so L1 entries don't outlive their L2 counterparts. Race condition: if TTL returns -2/0, backfill is skipped.
- **Evict order**: L2 cleared before L1 to minimize TOCTOU window.
- **Cache key format**: `prefix([arg1, arg2, ...])` — prefix priority: `cacheName` > `keyPrefix` > `fullyQualifiedClassName.methodName`. Max 256 chars; overflow gets truncated with SHA-256 hash suffix (first 16 hex chars).
- **Single Caffeine Cache**: Per-entry `Expiry` replaces the old multi-Cache-by-TTL design, preventing dynamic TTL from creating unbounded Cache instances.

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
# Only clear() and clearByPrefix(prefix) increment version numbers.
```

## Conventions

- **No Lombok**: All classes use hand-written getters/setters/loggers. Logger field name is `LOG`.
- **Chinese comments and Javadoc**: The codebase uses Chinese for documentation. Match this style for consistency.
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
| `reconciliation.enabled` | true | Enable version reconciliation for Pub/Sub loss compensation |
| `reconciliation.interval` | 30 | Reconciliation check interval in seconds |

## Test Structure

- Core 单元测试位于 `bing-cache-core/src/test/java/.../` — Mockito for mocking Redis, JUnit 5
- Core 集成测试：`CompositeCacheManagerIntegrationTest`, `ReconciliationIntegrationTest` — use Testcontainers (redis:7-alpine), requires Docker
- Reactor 集成测试模块：`bing-cache-test/`，用于验证依赖 reactor 构建 core artifact 的集成场景
- 测试数量以 Maven 测试输出为准，避免文档中的固定计数随用例增减而过期
