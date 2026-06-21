# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This module exists specifically to test and demonstrate the `bing-cache` core module inside the parent Maven reactor. It is a small Spring Boot Maven application that depends on the reactor-built `cn.com.bingbing:bing-cache:${project.version}` artifact from `../bing-cache-core`. The code is intentionally demo/test oriented: service methods simulate database work with sleeps or timestamps so cache hits, expiry, and eviction can be observed via tests or HTTP endpoints.

## Common Commands

There is no Maven wrapper in this repository; run commands from the parent repository root with the system `mvn` command.

```bash
# Compile this module and any required reactor dependencies
mvn -pl bing-cache-test -am compile

# Run all tests in this module and build required reactor dependencies
mvn -pl bing-cache-test -am test

# Run the BingCache test class only
mvn -pl bing-cache-test -am -Dtest=BingCacheTest test

# Run a single JUnit test method
mvn -pl bing-cache-test -am -Dtest=BingCacheTest#testBasicCache test

# Start the demo application on port 8080
mvn -pl bing-cache-test -am spring-boot:run

# Package this module and any required reactor dependencies
mvn -pl bing-cache-test -am package
```

Notes:
- Tests and the running app load `src/main/resources/application.yml`, which enables Redis-backed L2 caching and points Spring Redis at `cmac-mini:6379` with database `0`.
- The module depends on `cn.com.bingbing:bing-cache:${project.version}` through the current Maven reactor; use `-am` so Maven builds `bing-cache-core` before this module.
- The source module under test is `../bing-cache-core`. Read the parent `README.md` for basic usage/background when investigating module behavior.

## Testing Intent

This module is specifically for testing the reactor-built `bing-cache` core module. When working here, prefer changes and investigations that expose actual `bing-cache` behavior. If a potential `bing-cache` problem is found, try to write or run a focused test that demonstrates it and report the result clearly.

## Architecture

- `DemoApplication` is the Spring Boot entry point.
- `DemoService` contains simple `@BingCache` examples used by the basic `/api` endpoints and performance/null caching tests. Methods deliberately sleep and include timestamps so cache hits are visible.
- `CacheDemoExamples` is a catalog of `@BingCache` scenarios: custom `keyPrefix`, caching or not caching nulls, short/long TTLs, multi-parameter keys, object parameters, list returns, and nested business DTOs.
- `BingCacheDemos` focuses on eviction behavior with `@BingCacheEvict`: exact key eviction with `argIndexes`, fallback/prefix-style clearing, `allEntries`, `keyPrefix` pairing, and `beforeInvocation` behavior.
- `DemoController`, `CacheTestController`, and `BingCacheDemoController` expose the same scenarios over HTTP for manual verification. `BingCacheDemoController` also exposes manual cache operations through the injected `com.bing.cache.cache.CacheManager`.
- `BingCacheTest` is the main regression suite. It starts the full Spring context, clears the `CacheManager` before each test, and verifies cache hits, expiry, null caching, object/list caching, performance differences, and eviction semantics.

## Configuration

`src/main/resources/application.yml` configures:

- Bing Cache Caffeine L1 max size/TTL.
- Redis L2 caching, key prefix `demo-cache:`, and invalidation channel `demo-cache:invalidation`.
- Reconciliation enabled every 30 seconds.
- Spring Redis connection details.
- Debug logging for `com.bing.cache` and `com.example.demo`.

## Manual HTTP Checks

After `mvn -pl bing-cache-test -am spring-boot:run`, useful endpoints include:

```text
GET    /api/user/{id}
GET    /cache-test/status
GET    /cache-test/basic?userId=1001
GET    /cache-test/batch
GET    /demo/guide
GET    /demo/user/{id}
POST   /demo/user/update/{id}?name=Tom
GET    /demo/users?category=vip&keyword=a&page=1
POST   /demo/users/clear?category=vip&keyword=any&page=1
DELETE /demo/cache/all
```

Use `/demo/guide` for the in-app sequence of manual cache/eviction checks.
