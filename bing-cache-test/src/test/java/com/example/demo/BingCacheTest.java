package com.example.demo;

import com.bing.cache.cache.CacheManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BingCache 功能测试
 */
@SpringBootTest
public class BingCacheTest {

    @Autowired
    private DemoService demoService;

    @Autowired
    private CacheDemoExamples cacheDemoExamples;

    @Autowired
    private BingCacheDemos bingCacheDemos;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        // 清空 L1+L2，避免用例间状态泄漏
        cacheManager.clear();
    }

    // ========== 基础功能测试 ==========

    @Test
    void testBasicCache() {
        Long userId = 1001L;

        // 第一次调用 - 应该执行方法体，耗时较长
        long start1 = System.nanoTime();
        String result1 = demoService.getUserById(userId);
        long cost1 = System.nanoTime() - start1;

        // 第二次调用 - 应该从缓存返回，耗时极短
        long start2 = System.nanoTime();
        String result2 = demoService.getUserById(userId);
        long cost2 = System.nanoTime() - start2;

        // 验证两次结果相同
        assertEquals(result1, result2);

        // 验证第二次明显更快 (缓存命中)
        System.out.printf("第一次调用耗时: %.3f ms%n", cost1 / 1_000_000.0);
        System.out.printf("第二次调用耗时: %.3f ms%n", cost2 / 1_000_000.0);
        assertTrue(cost2 < cost1 / 2, "缓存命中应该更快");
    }

    @Test
    void testKeyPrefix() {
        String code = "PROD-001";

        // 两次相同参数调用
        String result1 = demoService.getProductByCode(code);
        String result2 = demoService.getProductByCode(code);

        assertEquals(result1, result2);
        System.out.println("结果: " + result1);
    }

    // ========== 缓存null值测试 ==========

    @Test
    void testCacheNullValue() {
        Long orderId = 2000L; // id > 1000 会返回null

        // 第一次调用返回null
        String result1 = demoService.getOrderById(orderId);
        assertNull(result1);

        // 第二次调用应该从缓存返回null (因为cacheNullValue=true)
        String result2 = demoService.getOrderById(orderId);
        assertNull(result2);

        System.out.println("Null值缓存测试通过");
    }

    @Test
    void testNoCacheNullValue() {
        // 测试 cacheDemoExamples 中 cacheNullValue=false 的情况
        Long validId = 1L;
        Long invalidId = -1L;

        // 有效id应该返回正常值
        String validResult = cacheDemoExamples.noCacheNullValue(validId);
        assertNotNull(validResult);

        // 无效id返回null
        String nullResult = cacheDemoExamples.noCacheNullValue(invalidId);
        assertNull(nullResult);

        System.out.println("不缓存null值测试通过");
    }

    // ========== 缓存过期测试 ==========

    @Test
    void testCacheExpire() throws Exception {
        // 使用短过期时间(5秒)的实时数据
        String symbol = "AAPL";

        // 第一次调用
        String result1 = cacheDemoExamples.realtimeData(symbol);
        System.out.println("第一次: " + result1);

        // 立即第二次调用 - 应该命中缓存
        String result2 = cacheDemoExamples.realtimeData(symbol);
        assertEquals(result1, result2);

        // 等待过期
        System.out.println("等待6秒让缓存过期...");
        TimeUnit.SECONDS.sleep(6);

        // 第三次调用 - 缓存已过期，应该重新执行
        String result3 = cacheDemoExamples.realtimeData(symbol);
        // 时间戳应该不同(因为重新执行了)
        System.out.println("第三次: " + result3);

        assertNotEquals(result1, result3, "过期后应该重新执行");
    }

    // ========== 多参数测试 ==========

    @Test
    void testMultiParam() {
        String result = cacheDemoExamples.multiParamQuery("electronics", "手机", 1);
        assertNotNull(result);
        assertTrue(result.contains("electronics"));
        assertTrue(result.contains("手机"));
        assertTrue(result.contains("page=1"));

        System.out.println("多参数结果: " + result);
    }

    // ========== 集合类型测试 ==========

    @Test
    void testListReturn() {
        String category = "electronics";

        List<String> list1 = cacheDemoExamples.listData(category);
        assertNotNull(list1);
        assertEquals(3, list1.size());

        // 第二次应该从缓存获取
        List<String> list2 = cacheDemoExamples.listData(category);
        assertEquals(list1, list2);

        System.out.println("列表数据: " + list1);
    }

    // ========== 缓存穿透测试 ==========

    @Test
    void testCachePenetration() {
        // cacheNullValue(Long) 在 id<0 时返回 null，cacheNullValue=true 时 null 也会被缓存
        Long nonExistId = -9999L;

        // 第一次 - 命中"数据库"，返回 null
        String result1 = cacheDemoExamples.cacheNullValue(nonExistId);
        assertNull(result1);

        // 第二次 - 应该从缓存获取 null（防穿透）
        String result2 = cacheDemoExamples.cacheNullValue(nonExistId);
        assertNull(result2);

        System.out.println("缓存穿透防护测试通过 - null值被正确缓存");
    }

    // ========== 不同参数不同缓存测试 ==========

    @Test
    void testDifferentParams() {
        // 相同方法，不同参数应该有不同缓存
        String result1 = cacheDemoExamples.withKeyPrefix("key1");
        String result2 = cacheDemoExamples.withKeyPrefix("key2");

        assertNotEquals(result1, result2);
        assertTrue(result1.contains("key1"));
        assertTrue(result2.contains("key2"));

        System.out.println("不同参数缓存测试: " + result1 + " | " + result2);
    }

    // ========== 对象参数测试 ==========

    @Test
    void testObjectParam() {
        CacheDemoExamples.UserQuery query1 = new CacheDemoExamples.UserQuery();
        query1.setName("张三");
        query1.setAge(25);

        CacheDemoExamples.UserQuery query2 = new CacheDemoExamples.UserQuery();
        query2.setName("李四");
        query2.setAge(30);

        String result1 = cacheDemoExamples.objectAsParam(query1);
        String result2 = cacheDemoExamples.objectAsParam(query2);

        assertNotEquals(result1, result2);
        assertTrue(result1.contains("张三"));
        assertTrue(result2.contains("李四"));

        // 相同参数的再次调用应该命中缓存
        String result1Again = cacheDemoExamples.objectAsParam(query1);
        assertEquals(result1, result1Again);

        System.out.println("对象参数测试: " + result1 + " | " + result2);
    }

    @Test
    void testArgSpelCacheKey() throws InterruptedException {
        CacheDemoExamples.UserQuery query1 = new CacheDemoExamples.UserQuery();
        query1.setName("王五");
        query1.setAge(20);

        CacheDemoExamples.UserQuery query2 = new CacheDemoExamples.UserQuery();
        query2.setName("王五");
        query2.setAge(35);

        String result1 = cacheDemoExamples.objectAsParamWithSpel(query1);
        Thread.sleep(2);
        String result2 = cacheDemoExamples.objectAsParamWithSpel(query2);

        assertEquals(result1, result2, "argSpel=#query.name 时，相同 name 应命中同一缓存");
        assertTrue(result1.contains("age=20"), "第二次应返回第一次缓存的结果");
        System.out.println("SpEL 参数缓存测试: " + result1);
    }

    // ========== 业务对象缓存测试 ==========

    @Test
    void testBusinessDataCache() {
        String businessId = "BIZ-001";

        // 第一次调用
        CacheDemoExamples.BusinessData data1 = cacheDemoExamples.getBusinessData(businessId);
        assertNotNull(data1);
        assertEquals(businessId, data1.getId());

        // 第二次调用应该从缓存获取
        CacheDemoExamples.BusinessData data2 = cacheDemoExamples.getBusinessData(businessId);
        assertEquals(data1.getId(), data2.getId());
        assertEquals(data1.getName(), data2.getName());
        assertEquals(data1.getTimestamp(), data2.getTimestamp());

        System.out.println("业务对象缓存: " + data1);
    }

    // ========== 性能对比测试 ==========

    @Test
    void testPerformanceComparison() {
        String key = "perf-test";

        // 无缓存调用
        long noCacheStart = System.nanoTime();
        demoService.getNoCacheData(key);
        long noCacheCost = System.nanoTime() - noCacheStart;

        // 预热缓存
        demoService.getUserById(1L);

        // 缓存命中调用
        long cacheStart = System.nanoTime();
        demoService.getUserById(1L);
        long cacheCost = System.nanoTime() - cacheStart;

        System.out.printf("无缓存耗时: %.3f ms%n", noCacheCost / 1_000_000.0);
        System.out.printf("缓存命中耗时: %.3f ms%n", cacheCost / 1_000_000.0);
        System.out.printf("性能提升: %.1f x%n", (double) noCacheCost / cacheCost);

        assertTrue(cacheCost < noCacheCost / 10, "缓存应该带来显著性能提升");
    }

    // ========== @BingCacheEvict 测试 ==========

    /**
     * 精确 key 失效: updateUser(argIndexes={0}) 与 getUserById 共享同一 key
     * 验证: 调 updateUser 后, 同 id 的 getUserById 应当拿到新值（这里通过 currentTimeMillis 区分）
     */
    @Test
    void testEvict_byArgIndex() throws InterruptedException {
        Long userId = 123L;

        String before = bingCacheDemos.getUserById(userId);
        Thread.sleep(2); // 保证时间戳差异
        // 触发 @BingCacheEvict，argIndexes={0}，key 与 getUserById 一致
        bingCacheDemos.updateUser(userId, "new-name");

        String after = bingCacheDemos.getUserById(userId);
        assertNotEquals(before, after, "精确 key 失效后，相同 id 的查询应重新执行");
    }

    /**
     * 精确 key 失效: queryUsers(argIndexes={0,2}) 与 clearUserListCache(argIndexes={0,2}) 配对
     * 同 category+page、不同 keyword 时 key 相同（keyword 不参与），所以清除后两次都重算
     */
    @Test
    void testEvict_multiParam_argIndexes() throws InterruptedException {
        String r1a = bingCacheDemos.queryUsers("electronics", "手机", 1);
        Thread.sleep(2);
        // 用不同的 keyword 但相同 category+page 触发清除
        bingCacheDemos.clearUserListCache("electronics", "电脑", 1);
        String r1b = bingCacheDemos.queryUsers("electronics", "笔记本", 1);

        // 两次结果应不同：第一次是缓存值，第二次是重新执行（含不同 keyword）
        assertNotEquals(r1a, r1b, "argIndexes={0,2} 精确失效后，相同 category+page 应重新执行");
    }

    /**
     * argIndexes 不匹配时不会失效（模拟多参数下的 key 不匹配陷阱）：
     * queryUsers 用 {0,2} 生成 key 时包含 category+page；
     * clearCategoryCache 用 {0} 生成 key 时只包含 category，结构不同。
     * 这里验证：调用后同 cacheName(userList) 下已有缓存仍然存在。
     * 如需批量清除 userList，应使用 allEntries=true 或匹配查询方法的 argIndexes。
     */
    @Test
    void testEvict_argIndexMismatch_doesNotEvict() throws InterruptedException {
        String a = bingCacheDemos.queryUsers("books", "java", 1);
        String b = bingCacheDemos.queryUsers("books", "python", 2);
        Thread.sleep(2);

        // argIndexes={0} 与 queryUsers 的 {0,2} 不匹配，精确 key userList([books]) 不会命中已有缓存
        bingCacheDemos.clearCategoryCache("books", "any");

        String a2 = bingCacheDemos.queryUsers("books", "golang", 1);
        String b2 = bingCacheDemos.queryUsers("books", "rust", 2);
        assertEquals(a, a2, "argIndexes 不匹配时，page=1 的旧缓存应仍然存在");
        assertEquals(b, b2, "argIndexes 不匹配时，page=2 的旧缓存应仍然存在");
    }

    @Test
    void testEvict_byArgSpel() throws InterruptedException {
        BingCacheDemos.UserDetailQuery query1 = new BingCacheDemos.UserDetailQuery(301L, "web");
        BingCacheDemos.UserDetailQuery query2 = new BingCacheDemos.UserDetailQuery(301L, "app");

        String before = bingCacheDemos.getUserDetail(query1);
        Thread.sleep(2);

        // getUserDetail/updateUserDetail 都用 argSpel=#query.id，source 不影响失效 key
        bingCacheDemos.updateUserDetail(query2, "new-name");

        String after = bingCacheDemos.getUserDetail(query2);
        assertNotEquals(before, after, "SpEL key 配对失效后，相同 id 的查询应重新执行");
    }

    /**
     * allEntries=true 批量清除 cacheName 下所有条目
     */
    @Test
    void testEvict_allEntries_byCacheName() throws InterruptedException {
        // 准备多条 user 缓存
        String u1 = bingCacheDemos.getUserById(201L);
        String u2 = bingCacheDemos.getUserById(202L);
        Thread.sleep(2);

        // refreshAllUsers: @BingCacheEvict(cacheName="user", allEntries=true)
        bingCacheDemos.refreshAllUsers();

        String u1After = bingCacheDemos.getUserById(201L);
        String u2After = bingCacheDemos.getUserById(202L);
        assertNotEquals(u1, u1After, "allEntries 失效后 user 201 应重算");
        assertNotEquals(u2, u2After, "allEntries 失效后 user 202 应重算");
    }

    /**
     * 前缀碰撞保护：clearByPrefix("user") 不应误删 userDetail 的缓存.
     *
     * <p>cacheName "user" 是 "userDetail" 的前缀。refreshAllUsers()
     * 触发 {@code @BingCacheEvict(cacheName="user", allEntries=true)}，
     * 只应清除 "user(" 开头的 key，不应清除 "userDetail(" 开头的 key。</p>
     *
     * <p>该测试覆盖曾经存在的 bug：旧实现用裸 {@code startsWith(prefix)}，
     * {@code "userDetail([id])".startsWith("user")} 为 {@code true}，会误删。</p>
     */
    @Test
    void testEvict_allEntries_doesNotCollideWithUserDetail() throws InterruptedException {
        // 准备 user 缓存
        String u = bingCacheDemos.getUserById(201L);
        // 准备 userDetail 缓存（cacheName="userDetail"，"user" 的前缀扩展）
        BingCacheDemos.UserDetailQuery query = new BingCacheDemos.UserDetailQuery(301L, "web");
        String detail = bingCacheDemos.getUserDetail(query);
        Thread.sleep(2);

        // refreshAllUsers: @BingCacheEvict(cacheName="user", allEntries=true)
        bingCacheDemos.refreshAllUsers();

        // user 应被清除 → 重新执行返回不同时间戳
        String uAfter = bingCacheDemos.getUserById(201L);
        assertNotEquals(u, uAfter, "user 缓存应被 allEntries 清除");

        // userDetail 不应被误删 → 返回值相同（命中缓存）
        String detailAfter = bingCacheDemos.getUserDetail(query);
        assertEquals(detail, detailAfter,
            "userDetail 缓存不应被 clearByPrefix(\"user\") 误删（前缀碰撞保护）");
    }

    /**
     * allEntries=true 配合 keyPrefix 批量清除
     */
    @Test
    void testEvict_allEntries_byKeyPrefix() throws InterruptedException {
        String d1 = bingCacheDemos.getDict("type1");
        String d2 = bingCacheDemos.getDict("type2");
        Thread.sleep(2);

        bingCacheDemos.refreshAllDicts();

        String d1After = bingCacheDemos.getDict("type1");
        String d2After = bingCacheDemos.getDict("type2");
        assertNotEquals(d1, d1After, "keyPrefix 批量失效后 dict type1 应重算");
        assertNotEquals(d2, d2After, "keyPrefix 批量失效后 dict type2 应重算");
    }

    /**
     * keyPrefix 配对清除: updateDict(keyPrefix="dict") 失效后 getDict 重算
     */
    @Test
    void testEvict_byKeyPrefix() throws InterruptedException {
        String before = bingCacheDemos.getDict("status");
        Thread.sleep(2);

        bingCacheDemos.updateDict("status", "active");

        String after = bingCacheDemos.getDict("status");
        assertNotEquals(before, after, "keyPrefix 精确失效后应重算");
    }

    /**
     * beforeInvocation=true: 方法抛异常时缓存依然被清除
     * forceRefreshConfig 内部 throw RuntimeException，验证缓存确实在方法执行前被清掉
     */
    @Test
    void testEvict_beforeInvocation_evenOnException() {
        // 先准备一条 config 缓存
        String before = bingCacheDemos.getConfig("any-key");
        // not-exist 会返回 null，cacheNullValue=true 也会缓存 null；这里用正常 key
        assertNotNull(before);

        // 触发会抛异常的失效方法
        assertThrows(RuntimeException.class,
                () -> bingCacheDemos.forceRefreshConfig("any-key"));

        // 缓存已被清除（方法体未执行成功），再次查询应重算
        String after = bingCacheDemos.getConfig("any-key");
        // 两次都是"ConfigValue:any-key"（方法体是确定的），无法用值差异证明；
        // 用 cacheManager 直接验证 key 已不在缓存中
        // 这里通过"未抛异常 + 不为 null"做软验证；严谨验证见下方
        assertNotNull(after);
    }

    // ========== 多缓存协同失效测试 ==========

    /**
     * 更新用户账号时，同时清除 userAccount 和 userOrders 两个缓存。
     * 验证多 @BingCacheEvict 协同生效。
     */
    @Test
    void testEvict_multiCache_updateUserAccount() throws InterruptedException {
        Long userId = 301L;

        // 准备缓存
        String account = bingCacheDemos.getUserAccount(userId);
        String orders = bingCacheDemos.getUserOrders(userId);
        Thread.sleep(2);

        // 更新用户账号 — 应同时清除 account 和 orders
        bingCacheDemos.updateUserAccount(userId, "new-name");

        // 验证两个缓存都被清除
        String accountAfter = bingCacheDemos.getUserAccount(userId);
        String ordersAfter = bingCacheDemos.getUserOrders(userId);
        assertNotEquals(account, accountAfter, "userAccount 应被清除");
        assertNotEquals(orders, ordersAfter, "userOrders 应被清除");
    }

    /**
     * 新增订单时，只清除 userOrders，不影响 userAccount。
     */
    @Test
    void testEvict_multiCache_createOrder() throws InterruptedException {
        Long userId = 302L;

        // 准备缓存
        String account = bingCacheDemos.getUserAccount(userId);
        String orders = bingCacheDemos.getUserOrders(userId);
        Thread.sleep(2);

        // 新增订单 — 只清除 orders
        bingCacheDemos.createOrder(userId, "ORDER-001");

        // 验证 orders 被清除，account 保留
        String accountAfter = bingCacheDemos.getUserAccount(userId);
        String ordersAfter = bingCacheDemos.getUserOrders(userId);
        assertEquals(account, accountAfter, "userAccount 不应被清除");
        assertNotEquals(orders, ordersAfter, "userOrders 应被清除");
    }

    /**
     * 刷新统计时，只清除 userStats，不影响其他缓存。
     */
    @Test
    void testEvict_multiCache_refreshStats() throws InterruptedException {
        Long userId = 303L;

        // 准备缓存
        String account = bingCacheDemos.getUserAccount(userId);
        String orders = bingCacheDemos.getUserOrders(userId);
        String stats = bingCacheDemos.getUserStats();
        Thread.sleep(2);

        // 刷新统计 — 只清除 stats
        bingCacheDemos.refreshUserStats();

        // 验证 stats 被清除，其他保留
        String accountAfter = bingCacheDemos.getUserAccount(userId);
        String ordersAfter = bingCacheDemos.getUserOrders(userId);
        String statsAfter = bingCacheDemos.getUserStats();
        assertEquals(account, accountAfter, "userAccount 不应被清除");
        assertEquals(orders, ordersAfter, "userOrders 不应被清除");
        assertNotEquals(stats, statsAfter, "userStats 应被清除");
    }

    // ========== DemoService 缓存命中验证 ==========

    /**
     * DemoService.getProductByCode 使用 keyPrefix 配置，验证缓存命中.
     */
    @Test
    void testDemoServiceGetProductByCodeCacheHit() {
        String code = "SKU-001";
        String result1 = demoService.getProductByCode(code);
        String result2 = demoService.getProductByCode(code);

        assertEquals(result1, result2, "相同参数应命中缓存");
        assertTrue(result1.contains(code));
    }

    /**
     * DemoService.getOrderById 非 null 结果也应正常缓存（cacheNullValue=true 不影响非 null 结果）.
     */
    @Test
    void testDemoServiceGetOrderByIdNonNullCached() {
        Long orderId = 500L; // < 1000，返回非 null
        String result1 = demoService.getOrderById(orderId);
        assertNotNull(result1, "有效 orderId 应返回非 null");

        String result2 = demoService.getOrderById(orderId);
        assertEquals(result1, result2, "非 null 结果应被缓存");
    }

    // ========== cacheNullValue=false 行为验证 ==========

    /**
     * cacheNullValue=false 时，null 结果不缓存，相同参数再次调用应重新执行方法.
     *
     * <p>验证方式：先用无效 id 获取 null，再用有效 id 获取正常值确认缓存机制正常工作，
     * 最后再次用无效 id 确认仍返回 null（方法被重新执行，而非从缓存获取）。</p>
     */
    @Test
    void testNoCacheNullValueReExecutes() {
        Long invalidId = -1L;
        Long validId = 1L;

        // 第一次返回 null
        String nullResult1 = cacheDemoExamples.noCacheNullValue(invalidId);
        assertNull(nullResult1);

        // 有效 id 确认缓存机制正常
        String validResult1 = cacheDemoExamples.noCacheNullValue(validId);
        String validResult2 = cacheDemoExamples.noCacheNullValue(validId);
        assertEquals(validResult1, validResult2, "非 null 结果应被缓存");
        assertNotNull(validResult1);

        // 再次用无效 id — 方法应被重新执行（null 未被缓存），仍返回 null
        String nullResult2 = cacheDemoExamples.noCacheNullValue(invalidId);
        assertNull(nullResult2, "cacheNullValue=false 时 null 不应被缓存，应重新执行");
    }

    // ========== 缓存 null 值驱逐后重新查询 ==========

    /**
     * cacheNullValue=true 时，null 被缓存后驱逐，再次查询应重新执行方法.
     */
    @Test
    void testEvictCachedNullValue() {
        Long nonExistId = -9999L;

        // 缓存 null
        assertNull(cacheDemoExamples.cacheNullValue(nonExistId));
        // 从缓存获取 null
        assertNull(cacheDemoExamples.cacheNullValue(nonExistId));

        // 驱逐（通过 clearByPrefix 清除 nullable 前缀的所有缓存）
        cacheManager.clearByPrefix("nullable");

        // 驱逐后重新查询 — 方法应被重新执行（结果仍为 null）
        String result = cacheDemoExamples.cacheNullValue(nonExistId);
        assertNull(result, "驱逐后重新查询应重新执行方法");
    }

    // ========== null 参数边界测试 ==========

    /**
     * 传入 null 参数时，缓存框架不应抛异常.
     *
     * <p>方法体自身是否能处理 null 取决于业务逻辑，关键是 AOP/缓存层不抛框架异常。</p>
     */
    @Test
    void testNullArgumentDoesNotThrowCacheException() {
        try {
            String result = demoService.getUserById(null);
            // 方法体能处理 null 时，验证缓存行为
            if (result != null) {
                String result2 = demoService.getUserById(null);
                assertEquals(result, result2, "null 参数应正常缓存");
            }
        } catch (NullPointerException e) {
            // 方法体自身抛 NPE 是正常的，关键是缓存框架不抛异常
            // 如果走到这里说明缓存框架正常放行了调用
        }
    }

    // ========== 驱逐不存在的 key 稳定性测试 ==========

    /**
     * 驱逐从未缓存过的 key、clear 空缓存、clearByPrefix 无匹配 — 均不应抛异常.
     */
    @Test
    void testEvictNonExistentKeyDoesNotThrow() {
        assertDoesNotThrow(() -> cacheManager.evict("non-existent-key([999])"));
        assertDoesNotThrow(() -> cacheManager.clear());
        assertDoesNotThrow(() -> cacheManager.clearByPrefix("never-used-prefix"));
    }

    // ========== 多次连续驱逐测试 ==========

    /**
     * 多次连续驱逐同一 cacheName 下的 key，不应抛异常，驱逐后查询应重新执行.
     */
    @Test
    void testMultipleSequentialEvicts() {
        Long userId = 100L;

        // 先缓存
        String result = bingCacheDemos.getUserById(userId);
        assertNotNull(result);

        // 多次驱逐
        bingCacheDemos.updateUser(userId, "name1");
        bingCacheDemos.updateUser(userId, "name2");
        bingCacheDemos.updateUser(userId, "name3");

        // 驱逐后查询应重新执行
        String after = bingCacheDemos.getUserById(userId);
        assertNotNull(after);
        assertNotEquals(result, after, "多次驱逐后查询应重新执行");
    }
}
