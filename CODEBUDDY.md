# CODEBUDDY.md

This file provides guidance to CodeBuddy Code when working with code in this repository.

## Project Overview

Bing Cache is a Spring AOP-based method-level caching component distributed as a Spring Boot Starter. It provides transparent caching via annotations with a two-tier architecture: L1 local cache (Caffeine) and L2 distributed cache (Redis). Base package: `com.bing.cache`.

## Build & Test Commands

```bash
# Full build (compile + tests)
mvn clean verify

# Compile only (skips tests)
mvn compile

# Run all unit tests
mvn test

# Run a single test class
mvn test -Dtest=CacheAspectTest

# Run a single test method
mvn test -Dtest=CacheAspectTest#shouldReturnCachedValue

# Integration tests (requires Docker for Testcontainers)
mvn test -Dtest=CompositeCacheManagerIntegrationTest
```

**JDK requirement**: Must use JDK 21. System default JDK 8 will cause Caffeine 3.x class loading failures.

**Windows note**: The `mvn` bash wrapper may fail under Git Bash with classpath errors; run Maven via PowerShell if this happens.

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

- **Single Cache + per-entry Expiry**: Uses one Caffeine Cache instance with `CacheEntry` record carrying per-entry `expireNanos`. `CacheEntryExpiry` implements Caffeine's `Expiry` interface for per-entry expiration. This replaces an older multi-Cache-by-TTL design that could create unbounded Cache instances under dynamic TTLs.
- **L1 max TTL**: `l1MaxTtlSeconds` caps all L1 entry lifetimes. Applied in `put()` via `min(expireSeconds, l1MaxTtlSeconds)`. When `expireSeconds <= 0` (no expiry) but `l1MaxTtlSeconds > 0`, uses `l1MaxTtlSeconds` instead.
- **`keys()` method**: Exposes `cache.asMap().keySet()` for reconciliation prefix checking.
- **`clearByPrefix(prefix)`**: O(n) scan over all keys (`removeIf(key -> key.startsWith(prefix))`). Acceptable for typical cache sizes but worth noting for very large caches.

#### CacheVersionStore

- Redis-backed version counter per cacheName. Key format: `{keyPrefix}version:{cacheName}`.
- Global version key: `{keyPrefix}version:__all__`.
- `incrementVersion(cacheName)` / `incrementAllVersion()` — called by CompositeCacheManager on `clear()` / `clearByPrefix(prefix)`. Single-key `evict()` does NOT increment (see Evict flow below).
- `getVersion(cacheName)` / `getActiveCacheNames()` — called by CacheReconciliationService during periodic checks.
- Registered as a single shared bean in `BingCacheAutoConfiguration` (consumed by both `CompositeCacheManager` and `CacheReconciliationService` via `ObjectProvider`).

#### CacheReconciliationService

- Implements `SmartLifecycle`, starts a single-thread `ScheduledExecutorService`.
- `reconcile()`: checks global version first; if changed, clears all L1. Then checks each active cacheName's version; if changed, clears L1 by prefix.
- First reconciliation runs immediately (initialDelay=0) to detect pre-existing version drift.
- Maintains `lastKnownVersions` map and `lastKnownAllVersion` to detect changes.

#### CompositeCacheManager

- **Backfill race fix**: `backfillL1()` checks `remainingTtl`: >0 → use it; -1 → L1 no expiry; -2/0 → skip backfill + warn log.
- **Version increment**: `clear()` calls `incrementAllVersion()`, `clearByPrefix(prefix)` calls `incrementVersion(prefix)`. Both guarded by `versionStore != null`. `evict(key)` does NOT increment version.
- **Redis recovery callback** (`RedisCacheManager.setRecoveryCallback()`, wired in auto-config): conditional — when reconciliation is enabled, the callback only logs and lets the reconciliation service clear L1 incrementally at the next cycle (avoids stampede); when reconciliation is disabled, the callback calls `l1CacheManager::clear` immediately.

### Auto-configuration conditions

- `CompositeCacheManager` activates when `RedisConnectionFactory` bean exists AND `bing.cache.redis.enabled=true` (default)
- `CaffeineCacheManager` activates as fallback via `@ConditionalOnMissingBean(CacheManager.class)`
- `CacheReconciliationService` activates when Redis is available AND `bing.cache.reconciliation.enabled=true` (default)
- Registered via `AutoConfiguration.imports` (Spring Boot 3.x). `spring.factories` has been deleted (Spring Boot 3.x no longer reads it for auto-configuration).

### Key design decisions

- **BingCacheNullValue sentinel**: Caffeine cannot store null. `BingCacheNullValue` (package-private, implements `NullValueSentinel`) is used as a placeholder. It is only stored in L1, never in L2 (Jackson can't deserialize the package-private class). `CompositeCacheManager` detects it via `instanceof NullValueSentinel` (type-safe, does not rely on class name strings). **Limitation**: `cacheNullValue=true` only prevents cache penetration on the local instance; other instances querying a non-existent id still hit the DB (NullValue is not written to L2).
- **L1 backfill carries L2 remaining TTL**: When L2 hits but L1 misses, `RedisCacheManager.getRemainingTtl()` fetches the remaining expiry so L1 entries don't outlive their L2 counterparts. Race condition: if TTL returns -2/0, backfill is skipped.
- **Evict order**: L2 cleared before L1 to minimize TOCTOU window.
- **Cache key format**: `prefix([arg1, arg2, ...])` — prefix priority: `cacheName` > `keyPrefix` > `fullyQualifiedClassName.methodName`. Max 256 chars; overflow gets truncated with SHA-256 hash suffix (first 16 hex chars). Custom objects are serialized via Jackson (not `toString()`) for deterministic cross-JVM consistency; Jackson failures throw `IllegalStateException` rather than degrading to `hashCode()` (identity hashCode changes across restarts, breaking the consistency promise).

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
- **Git commit format**: `[bing-cache] description` — Change-Id auto-generated by Gerrit hook, do not add manually. Jira number required.
- **MapStruct**: Listed as annotation processor in `pom.xml` but not currently used in source code.

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

`BingCacheProperties` is annotated with `@Validated` and uses Bean Validation (`@Min` constraints on `maxSize`, `l1MaxTtl`, `interval`). Invalid values fail binding at startup rather than causing runtime errors (e.g., `interval=0` would otherwise make `scheduleAtFixedRate` throw).

## Test Structure

- Unit tests in `src/test/java/.../` — Mockito for mocking Redis, JUnit 5
- Integration tests: `CompositeCacheManagerIntegrationTest`, `ReconciliationIntegrationTest` — use Testcontainers (`redis:7-alpine`), requires Docker
- 150 test cases total (count via `@Test` annotations)
