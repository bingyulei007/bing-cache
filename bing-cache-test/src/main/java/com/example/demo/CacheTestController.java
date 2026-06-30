package com.example.demo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 缓存功能演示接口
 * 用于手动测试各种缓存场景
 */
@RestController
@RequestMapping("/cache-test")
public class CacheTestController {

    @Autowired
    private DemoService demoService;

    @Autowired
    private CacheDemoExamples cacheDemoExamples;

    @Autowired
    private BingCacheDemos bingCacheDemos;

    // ========== 基础缓存测试 ==========

    /**
     * GET /cache-test/basic?userId=1001
     * 测试基础缓存功能
     * 连续调用两次，观察第二次是否命中缓存(耗时降低)
     */
    @GetMapping("/basic")
    public Map<String, Object> testBasic(@RequestParam Long userId) {
        Map<String, Object> result = new HashMap<>();

        // 第一次调用
        long start1 = System.nanoTime();
        String data1 = demoService.getUserById(userId);
        long cost1 = System.nanoTime() - start1;

        // 第二次调用
        long start2 = System.nanoTime();
        String data2 = demoService.getUserById(userId);
        long cost2 = System.nanoTime() - start2;

        result.put("第一次", Map.of("data", data1, "costMs", cost1 / 1_000_000.0));
        result.put("第二次", Map.of("data", data2, "costMs", cost2 / 1_000_000.0));
        result.put("加速比", String.format("%.1f x", (double) cost1 / cost2));
        result.put("缓存命中", cost2 < cost1 / 2);

        return result;
    }

    // ========== 缓存过期测试 ==========

    /**
     * GET /cache-test/expire?symbol=AAPL
     * 测试缓存过期 - realtimeData 使用5秒过期时间
     * 等待6秒后再次调用，观察数据变化
     */
    @GetMapping("/expire")
    public Map<String, Object> testExpire(@RequestParam(defaultValue = "AAPL") String symbol) {
        Map<String, Object> result = new HashMap<>();

        String data1 = cacheDemoExamples.realtimeData(symbol);
        result.put("立即调用", data1);

        // 模拟等待过期(实际可通过参数控制)
        result.put("提示", "等待5秒后再次调用会获得新数据");

        return result;
    }

    // ========== 缓存穿透测试 ==========

    /**
     * GET /cache-test/penetration?id=9999
     * 测试缓存穿透防护 - id > 1000 返回null
     * null值会被缓存，防止穿透
     */
    @GetMapping("/penetration")
    public Map<String, Object> testPenetration(@RequestParam Long id) {
        Map<String, Object> result = new HashMap<>();

        // 第一次调用
        String data1 = demoService.getOrderById(id);
        result.put("第一次结果", data1);
        result.put("第一次是否Null", data1 == null);

        // 第二次调用 - 如果cacheNullValue=true，则命中缓存的null
        String data2 = demoService.getOrderById(id);
        result.put("第二次结果", data2);
        result.put("第二次是否Null", data2 == null);

        // 多次调用测试
        for (int i = 0; i < 3; i++) {
            String data = demoService.getOrderById(id);
            result.put("第" + (i + 3) + "次", data == null ? "null(缓存)" : data);
        }

        return result;
    }

    // ========== 多参数缓存测试 ==========

    /**
     * GET /cache-test/multi?category=electronics&keyword=手机&page=1
     * 测试多参数缓存
     */
    @GetMapping("/multi")
    public Map<String, Object> testMultiParam(
            @RequestParam String category,
            @RequestParam String keyword,
            @RequestParam Integer page) {

        String result = cacheDemoExamples.multiParamQuery(category, keyword, page);

        Map<String, Object> response = new HashMap<>();
        response.put("category", category);
        response.put("keyword", keyword);
        response.put("page", page);
        response.put("result", result);

        return response;
    }

    // ========== 列表数据缓存测试 ==========

    /**
     * GET /cache-test/list?category=electronics
     * 测试列表类型返回值缓存
     */
    @GetMapping("/list")
    public Map<String, Object> testListCache(@RequestParam(defaultValue = "electronics") String category) {
        Map<String, Object> result = new HashMap<>();

        List<String> list1 = cacheDemoExamples.listData(category);

        long start = System.nanoTime();
        List<String> list2 = cacheDemoExamples.listData(category);
        long cost = System.nanoTime() - start;

        result.put("数据", list1);
        result.put("缓存命中耗时", cost / 1_000_000.0 + " ms");
        result.put("两次结果相同", list1.equals(list2));

        return result;
    }

    // ========== 对象参数缓存测试 ==========

    /**
     * POST /cache-test/object
     * 测试对象作为参数的缓存
     */
    @PostMapping("/object")
    public Map<String, Object> testObjectCache(@RequestBody CacheDemoExamples.UserQuery query) {
        Map<String, Object> result = new HashMap<>();

        long start1 = System.nanoTime();
        String data1 = cacheDemoExamples.objectAsParam(query);
        long cost1 = System.nanoTime() - start1;

        long start2 = System.nanoTime();
        String data2 = cacheDemoExamples.objectAsParam(query);
        long cost2 = System.nanoTime() - start2;

        result.put("查询参数", query);
        result.put("第一次", Map.of("data", data1, "costMs", cost1 / 1_000_000.0));
        result.put("第二次", Map.of("data", data2, "costMs", cost2 / 1_000_000.0));
        result.put("缓存命中", cost2 < cost1 / 2);

        return result;
    }


    // ========== 精确清除测试 ==========

    /**
     * GET /cache-test/evict/user?id=1&name=Alice
     * 测试 cacheName + argIndexes 的单 key 清除
     */
    @GetMapping("/evict/user")
    public Map<String, Object> testUserEvict(
            @RequestParam(defaultValue = "1") Long id,
            @RequestParam(defaultValue = "Alice") String name) {
        Map<String, Object> result = new HashMap<>();

        String first = bingCacheDemos.getUserById(id);
        String second = bingCacheDemos.getUserById(id);
        bingCacheDemos.updateUser(id, name);
        String afterEvict = bingCacheDemos.getUserById(id);

        result.put("第一次", first);
        result.put("第二次", second);
        result.put("清除方法", "updateUser");
        result.put("清除后", afterEvict);
        result.put("清除前是否命中", Objects.equals(first, second));
        result.put("清除后是否重算", !Objects.equals(second, afterEvict));
        return result;
    }

    /**
     * GET /cache-test/evict/dict?dictType=gender&value=new
     * 测试 keyPrefix 精确清除
     */
    @GetMapping("/evict/dict")
    public Map<String, Object> testDictEvict(
            @RequestParam(defaultValue = "gender") String dictType,
            @RequestParam(defaultValue = "new") String value) {
        Map<String, Object> result = new HashMap<>();

        String first = bingCacheDemos.getDict(dictType);
        String second = bingCacheDemos.getDict(dictType);
        bingCacheDemos.updateDict(dictType, value);
        String afterEvict = bingCacheDemos.getDict(dictType);

        result.put("第一次", first);
        result.put("第二次", second);
        result.put("清除方法", "updateDict");
        result.put("清除后", afterEvict);
        result.put("清除前是否命中", Objects.equals(first, second));
        result.put("清除后是否重算", !Objects.equals(second, afterEvict));
        return result;
    }

    /**
     * GET /cache-test/evict/user-detail?id=1&source=app&name=Alice
     * 测试 SpEL 精确清除
     */
    @GetMapping("/evict/user-detail")
    public Map<String, Object> testUserDetailEvict(
            @RequestParam(defaultValue = "1") Long id,
            @RequestParam(defaultValue = "app") String source,
            @RequestParam(defaultValue = "Alice") String name) {
        Map<String, Object> result = new HashMap<>();
        BingCacheDemos.UserDetailQuery query = new BingCacheDemos.UserDetailQuery(id, source);

        String first = bingCacheDemos.getUserDetail(query);
        String second = bingCacheDemos.getUserDetail(query);
        bingCacheDemos.updateUserDetail(query, name);
        String afterEvict = bingCacheDemos.getUserDetail(query);

        result.put("第一次", first);
        result.put("第二次", second);
        result.put("清除方法", "updateUserDetail");
        result.put("清除后", afterEvict);
        result.put("清除前是否命中", Objects.equals(first, second));
        result.put("清除后是否重算", !Objects.equals(second, afterEvict));
        return result;
    }


    /**
     * GET /cache-test/evict/multi-cache?userId=1&name=Alice
     * 测试多个 @BingCacheEvict 协同清除
     */
    @GetMapping("/evict/multi-cache")
    public Map<String, Object> testMultiCacheEvict(
            @RequestParam(defaultValue = "1") Long userId,
            @RequestParam(defaultValue = "Alice") String name) {
        Map<String, Object> result = new HashMap<>();

        String accountFirst = bingCacheDemos.getUserAccount(userId);
        String accountSecond = bingCacheDemos.getUserAccount(userId);
        String ordersFirst = bingCacheDemos.getUserOrders(userId);
        String ordersSecond = bingCacheDemos.getUserOrders(userId);

        bingCacheDemos.updateUserAccount(userId, name);

        String accountAfterEvict = bingCacheDemos.getUserAccount(userId);
        String ordersAfterEvict = bingCacheDemos.getUserOrders(userId);

        result.put("清除动作", "updateUserAccount");
        result.put("账号缓存", buildEvictResult(accountFirst, accountSecond, accountAfterEvict));
        result.put("订单缓存", buildEvictResult(ordersFirst, ordersSecond, ordersAfterEvict));
        return result;
    }

    /**
     * GET /cache-test/evict/group?id=1&dictType=type1
     * 测试 group 分组清除
     */
    @GetMapping("/evict/group")
    public Map<String, Object> testGroupEvict(
            @RequestParam(defaultValue = "1") Long id,
            @RequestParam(defaultValue = "type1") String dictType) {
        Map<String, Object> result = new HashMap<>();

        String userFirst = bingCacheDemos.getAdminUser(id);
        String userSecond = bingCacheDemos.getAdminUser(id);
        String dictFirst = bingCacheDemos.getAdminDict(dictType);
        String dictSecond = bingCacheDemos.getAdminDict(dictType);

        bingCacheDemos.clearAdminGroup();

        String userAfterEvict = bingCacheDemos.getAdminUser(id);
        String dictAfterEvict = bingCacheDemos.getAdminDict(dictType);

        result.put("清除动作", "clearAdminGroup");
        result.put("管理员用户缓存", buildEvictResult(userFirst, userSecond, userAfterEvict));
        result.put("管理员字典缓存", buildEvictResult(dictFirst, dictSecond, dictAfterEvict));
        return result;
    }

    /**
     * GET /cache-test/evict/all?userId=1&dictType=type1
     * 测试全局清除所有缓存
     */
    @GetMapping("/evict/all")
    public Map<String, Object> testClearAll(
            @RequestParam(defaultValue = "1") Long userId,
            @RequestParam(defaultValue = "type1") String dictType) {
        Map<String, Object> result = new HashMap<>();

        String userFirst = bingCacheDemos.getUserById(userId);
        String userSecond = bingCacheDemos.getUserById(userId);
        String dictFirst = bingCacheDemos.getDict(dictType);
        String dictSecond = bingCacheDemos.getDict(dictType);

        bingCacheDemos.clearAll();

        String userAfterEvict = bingCacheDemos.getUserById(userId);
        String dictAfterEvict = bingCacheDemos.getDict(dictType);

        result.put("清除动作", "clearAll");
        result.put("用户缓存", buildEvictResult(userFirst, userSecond, userAfterEvict));
        result.put("字典缓存", buildEvictResult(dictFirst, dictSecond, dictAfterEvict));
        return result;
    }

    // ========== 性能对比测试 ==========

    /**
     * GET /cache-test/performance
     * 对比有缓存和无缓存的性能差异
     */
    @GetMapping("/performance")
    public Map<String, Object> testPerformance() {
        Map<String, Object> result = new HashMap<>();

        // 预热
        demoService.getUserById(1L);

        // 测试缓存性能
        long cacheTotal = 0;
        int cacheIterations = 10;
        for (int i = 0; i < cacheIterations; i++) {
            long start = System.nanoTime();
            demoService.getUserById(1L);
            cacheTotal += System.nanoTime() - start;
        }
        double avgCacheMs = (cacheTotal / 1_000_000.0) / cacheIterations;

        // 测试无缓存性能
        long noCacheTotal = 0;
        int noCacheIterations = 3;
        for (int i = 0; i < noCacheIterations; i++) {
            long start = System.nanoTime();
            demoService.getNoCacheData("test-key");
            noCacheTotal += System.nanoTime() - start;
        }
        double avgNoCacheMs = (noCacheTotal / 1_000_000.0) / noCacheIterations;

        result.put("缓存平均耗时", String.format("%.3f ms", avgCacheMs));
        result.put("无缓存平均耗时", String.format("%.3f ms", avgNoCacheMs));
        result.put("性能提升", String.format("%.1f x", avgNoCacheMs / avgCacheMs));

        return result;
    }

    // ========== 批量测试 ==========

    /**
     * GET /cache-test/batch
     * 批量测试不同场景
     */
    @GetMapping("/batch")
    public Map<String, Object> testBatch() {
        Map<String, Object> result = new HashMap<>();

        // 1. 基础缓存
        long start1 = System.nanoTime();
        demoService.getUserById(1L);
        demoService.getUserById(1L);
        result.put("基础缓存", String.format("%.3f ms", (System.nanoTime() - start1) / 1_000_000.0));

        // 2. keyPrefix
        long start2 = System.nanoTime();
        demoService.getProductByCode("TEST-001");
        demoService.getProductByCode("TEST-001");
        result.put("keyPrefix", String.format("%.3f ms", (System.nanoTime() - start2) / 1_000_000.0));

        // 3. 长过期配置
        long start3 = System.nanoTime();
        cacheDemoExamples.configData("app.name");
        cacheDemoExamples.configData("app.name");
        result.put("长过期配置", String.format("%.3f ms", (System.nanoTime() - start3) / 1_000_000.0));

        // 4. 多参数
        long start4 = System.nanoTime();
        cacheDemoExamples.multiParamQuery("cat", "kw", 1);
        cacheDemoExamples.multiParamQuery("cat", "kw", 1);
        result.put("多参数", String.format("%.3f ms", (System.nanoTime() - start4) / 1_000_000.0));

        // 5. 列表
        long start5 = System.nanoTime();
        cacheDemoExamples.listData("batch-test");
        cacheDemoExamples.listData("batch-test");
        result.put("列表缓存", String.format("%.3f ms", (System.nanoTime() - start5) / 1_000_000.0));

        return result;
    }


    private Map<String, Object> buildEvictResult(String first, String second, String afterEvict) {
        Map<String, Object> item = new HashMap<>();
        item.put("第一次", first);
        item.put("第二次", second);
        item.put("清除后", afterEvict);
        item.put("清除前是否命中", Objects.equals(first, second));
        item.put("清除后是否重算", !Objects.equals(second, afterEvict));
        return item;
    }

    // ========== 缓存状态探测 ==========

    /**
     * GET /cache-test/status
     * 返回测试接口状态
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "running");
        result.put("测试接口", Map.of(
                "基础缓存", "GET /cache-test/basic?userId=1001",
                "过期测试", "GET /cache-test/expire?symbol=AAPL",
                "穿透测试", "GET /cache-test/penetration?id=9999",
                "多参数", "GET /cache-test/multi?category=x&keyword=y&page=1",
                "列表缓存", "GET /cache-test/list?category=electronics",
                "对象参数", "POST /cache-test/object",
                "性能对比", "GET /cache-test/performance",
                "批量测试", "GET /cache-test/batch"
        ));
        return result;
    }
}
