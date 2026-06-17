# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Bing Cache is a Spring AOP-based method-level caching component distributed as a Spring Boot Starter. It provides transparent caching via annotations with a two-tier architecture: L1 local cache (Caffeine) and L2 distributed cache (Redis). Base package: `com.bing.cache`.

## Build & Test Commands

```bash
# Full build (compile + checkstyle + tests)
mvn clean verify

# Compile only (skips tests and checkstyle validation)
mvn compile

# Run all unit tests
mvn test

# Run a single test class
mvn test -Dtest=CacheAspectTest

# Run a single test method
mvn test -Dtest=CacheAspectTest#shouldReturnCachedValue

# Integration tests (requires Docker for Testcontainers)
mvn test -Dtest=CompositeCacheManagerIntegrationTest

# Checkstyle validation (runs automatically during validate phase)
mvn validate
```

**JDK requirement**: Must use JDK 21. System default JDK 8 will cause Caffeine 3.x class loading failures.

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
- `incrementVersion(cacheName)` / `incrementAllVersion()` — called by CompositeCacheManager on evict/clear/clearByPrefix.
- `getVersion(cacheName)` / `getActiveCacheNames()` — called by CacheReconciliationService during periodic checks.

#### CacheReconciliationService

- Implements `SmartLifecycle`, starts a single-thread `ScheduledExecutorService`.
- `reconcile()`: checks global version first; if changed, clears all L1. Then checks each active cacheName's version; if changed, clears L1 by prefix.
- First reconciliation runs immediately (initialDelay=0) to detect pre-existing version drift.
- Maintains `lastKnownVersions` map and `lastKnownAllVersion` to detect changes.

#### CompositeCacheManager

- **Backfill race fix**: `backfillL1()` checks `remainingTtl`: >0 → use it; -1 → L1 no expiry; -2/0 → skip backfill + warn log.
- **Version increment**: `evict()` calls `incrementVersion(key)`, `clear()` calls `incrementAllVersion()`, `clearByPrefix(prefix)` calls `incrementVersion(prefix)`. All guarded by `versionStore != null`.
- **Redis recovery callback**: `RedisCacheManager.setRecoveryCallback()` is wired in auto-config to `l1CacheManager::clear`, ensuring L1 dirty data is cleared when Redis recovers from degradation.

### Auto-configuration conditions

- `CompositeCacheManager` activates when `RedisConnectionFactory` bean exists AND `bing.cache.redis.enabled=true` (default)
- `CaffeineCacheManager` activates as fallback via `@ConditionalOnMissingBean(CacheManager.class)`
- `CacheReconciliationService` activates when Redis is available AND `bing.cache.reconciliation.enabled=true` (default)
- Registered via both `spring.factories` (Spring Boot 2.x) and `AutoConfiguration.imports` (Spring Boot 3.x)

### Key design decisions

- **BingCacheNullValue sentinel**: Caffeine cannot store null. `BingCacheNullValue` (package-private) is used as a placeholder. It is only stored in L1, never in L2 (Jackson can't deserialize it). `CompositeCacheManager` detects it by class simple name `"BingCacheNullValue"`.
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
L2.evict(key) → L1.evict(key) → publisher.publishEvict(key) → versionStore.incrementVersion(key)
```

## Conventions

- **No Lombok**: All classes use hand-written getters/setters/loggers. Logger field name is `LOG`.
- **Checkstyle**: Runs at `validate` phase, fails the build on violations. Config at `check-style.xml`. Tests excluded from checkstyle via `<excludes>**/test/**</excludes>`.
- **Chinese comments and Javadoc**: The codebase uses Chinese for documentation. Match this style for consistency.
- **Git commit format**: `[bing-cache] description` — Change-Id auto-generated by Gerrit hook, do not add manually. Jira number required.
- **MapStruct**: Listed as annotation processor but not currently used in source code.

## Configuration Properties

Prefix: `bing.cache`

| Property | Default | Description |
|----------|---------|-------------|
| `caffeine.max-size` | 1000 | Caffeine Cache 的最大条目数 |
| `caffeine.l1-max-ttl` | 0 | L1 最大存活秒数，0 表示不限制 |
| `redis.enabled` | true | Enable L2 Redis cache (requires Redis on classpath) |
| `redis.key-prefix` | `bing-cache:` | Redis key namespace prefix |
| `redis.channel-name` | `bing-cache:invalidation` | Pub/Sub channel for cross-instance invalidation |
| `reconciliation.enabled` | true | Enable version reconciliation for Pub/Sub loss compensation |
| `reconciliation.interval` | 30 | Reconciliation check interval in seconds |

## Test Structure

- Unit tests in `src/test/java/.../` — Mockito for mocking Redis, JUnit 5
- Integration tests: `CompositeCacheManagerIntegrationTest`, `ReconciliationIntegrationTest` — use Testcontainers (redis:7-alpine), requires Docker
- 133+ test cases total
