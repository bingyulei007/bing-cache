# CacheTestController 手动清除场景设计

## 背景

`bing-cache-test` 模块中的 `CacheTestController` 已提供基础缓存、过期、缓存穿透、多参数、列表、对象参数、性能和批量测试入口，但没有暴露清除类手动 HTTP 场景。`BingCacheDemos` 已经包含较完整的 `@BingCacheEvict` 演示方法，因此本次只在 Controller 层补充手动入口，不改核心缓存逻辑。

## 目标

- 通过浏览器或 curl 一次调用即可观察清除前命中、执行清除、清除后重算的效果。
- 覆盖常见清除能力：单 key、`keyPrefix`、SpEL、多缓存协同、group 分组、全局清空。
- 保持 demo 代码简单直观，沿用当前 `Map<String, Object>` 返回风格。

## 非目标

- 不新增或调整核心缓存实现。
- 不重构已有 demo service。
- 不把手动 Controller 改造成自动化断言测试；已有测试继续由 `src/test` 承担。

## 方案

在 `CacheTestController` 注入 `BingCacheDemos`，新增“缓存清除测试”分区。每个接口都按固定流程返回结果：

1. 首次查询，写入缓存。
2. 第二次查询，验证命中缓存，结果应与首次一致。
3. 调用对应 `@BingCacheEvict` 方法。
4. 再次查询，验证缓存被清除后重新执行，结果应变化。

优先通过 `BingCacheDemos` 返回值中的递增序号判断是否重算，不依赖耗时阈值。

## 新增接口

### 单 key 清除

`GET /cache-test/evict/user?id=1&name=Alice`

使用 `getUserById(id)` 与 `updateUser(id, name)`，验证 `cacheName = "user"` + `argIndexes = {0}` 精确清除。

### keyPrefix 清除

`GET /cache-test/evict/dict?dictType=gender&value=new`

使用 `getDict(dictType)` 与 `updateDict(dictType, value)`，验证 `keyPrefix = "dict"` 配对清除。

### SpEL 清除

`GET /cache-test/evict/user-detail?id=1&source=app&name=Alice`

使用 `UserDetailQuery(id, source)`，验证 `argSpel = "#query.id"` 配对清除。

### 多缓存协同清除

`GET /cache-test/evict/multi-cache?userId=1&name=Alice`

预热 `getUserAccount(userId)` 和 `getUserOrders(userId)`，调用 `updateUserAccount(userId, name)` 后两个缓存都应重算。

### group 分组清除

`GET /cache-test/evict/group`

预热 `getAdminUser(id)` 和 `getAdminDict(dictType)`，调用 `clearAdminGroup()` 后 admin 组内缓存都应重算。

### 全局清空

`GET /cache-test/evict/all`

预热多个不同命名空间缓存，调用 `clearAll()` 后这些缓存都应重算。

## 返回结构

每个接口返回 `Map<String, Object>`，包含以下字段：

- `第一次`
- `第二次`
- `清除动作`
- `清除后`
- `清除前是否命中`
- `清除后是否重算`

多缓存场景按缓存项分别返回这些信息。

## 状态入口

更新 `/cache-test/status`，把新增清除接口加入“测试接口”列表，方便手动发现。

## 验证

实现后至少运行：

```bash
mvn test -pl bing-cache-test
```

若环境缺少测试依赖或 Redis/Docker 等外部条件导致无法完整运行，需要如实记录失败原因和已完成的编译/测试范围。
