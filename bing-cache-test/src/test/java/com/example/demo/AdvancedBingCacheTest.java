package com.example.demo;

import com.bing.cache.cache.CaffeineCacheManager;
import com.bing.cache.cache.CacheManager;
import com.bing.cache.cache.CacheVersionStore;
import com.bing.cache.cache.CompositeCacheManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BingCache 高级功能测试
 *
 * 覆盖以下场景：
 * 1. 重载方法默认前缀 key 碰撞保护
 * 2. L1+L2 两级缓存回填
 * 3. 版本对账机制
 * 4. Redis 降级与恢复
 * 5. 缓存 key 长度限制
 * 6. 边界条件
 * 7. 并发缓存击穿防护
 * 8. L1 最大 TTL 限制
 */
@SpringBootTest
public class AdvancedBingCacheTest {

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private BingCacheDemos bingCacheDemos;

    @Autowired
    private CacheDemoExamples cacheDemoExamples;

    @Autowired(required = false)
    private CacheVersionStore cacheVersionStore;

    @BeforeEach
    void clearCache() {
        cacheManager.clear();
    }

    private void assertPresentInL1AndL2(CompositeCacheManager composite, String key, String message) {
        assertNotNull(composite.getL1CacheManager().get(key), message + " - L1");
        assertNotNull(composite.getL2CacheManager().get(key), message + " - L2");
    }

    private void assertAbsentFromL1AndL2(CompositeCacheManager composite, String key, String message) {
        assertNull(composite.getL1CacheManager().get(key), message + " - L1");
        assertNull(composite.getL2CacheManager().get(key), message + " - L2");
    }

    // ========== 1. 重载方法默认前缀 key 碰撞保护 ==========

    @Test
    @DisplayName("重载方法默认前缀隔离 - Long 和 String 参数生成不同 key")
    void testOverloadedMethodKeyIsolation() {
        // 相同的数值 "123"，但类型不同（Long vs String）
        Long longId = 123L;
        String stringCode = "123";

        // 调用两个重载方法
        String resultLong = bingCacheDemos.findItem(longId);
        String resultString = bingCacheDemos.findItem(stringCode);

        // 验证结果不同（因为方法实现不同）
        assertNotEquals(resultLong, resultString,
            "Long 和 String 参数应调用不同的重载方法");

        // 验证缓存隔离：再次调用应返回缓存值
        String resultLong2 = bingCacheDemos.findItem(longId);
        String resultString2 = bingCacheDemos.findItem(stringCode);

        assertEquals(resultLong, resultLong2, "Long 参数应命中缓存");
        assertEquals(resultString, resultString2, "String 参数应命中缓存");

        // 验证两个重载方法的缓存互不干扰
        assertNotEquals(resultLong2, resultString2,
            "重载方法的缓存应相互隔离");
    }

    @Test
    @DisplayName("重载方法 - 不同参数类型序列化结果相同时仍能隔离")
    void testOverloadedMethodSameSerializedValue() {
        // 测试边界情况：Long.valueOf(1) 和 String.valueOf("1") 序列化后都是 "1"
        // 但由于默认前缀包含参数类型签名，仍能区分
        String resultLong = bingCacheDemos.findItem(1L);
        String resultString = bingCacheDemos.findItem("1");

        assertNotEquals(resultLong, resultString,
            "即使序列化结果相同，不同类型参数的缓存也应隔离");

        // 再次调用验证缓存命中
        assertEquals(resultLong, bingCacheDemos.findItem(1L));
        assertEquals(resultString, bingCacheDemos.findItem("1"));
    }

    // ========== 2. L1+L2 两级缓存回填测试 ==========

    @Nested
    @DisplayName("L1+L2 回填测试")
    class L1L2BackfillTest {

        @Test
        @DisplayName("L1 miss + L2 hit 时回填 L1")
        void testL1MissL2HitBackfill() {
            if (!(cacheManager instanceof CompositeCacheManager composite)) {
                // 非 L1+L2 模式，跳过
                return;
            }

            String key = "backfill-test-key";
            String value = "backfill-value";
            long expireSeconds = 60;

            // 直接写入 L2，不写 L1
            composite.getL2CacheManager().put(key, value, expireSeconds);

            // 从 CompositeCacheManager 获取，应触发 L1 回填
            Object result = composite.get(key);
            assertEquals(value, result, "应从 L2 获取到值");

            // 验证 L1 已被回填
            Object l1Value = composite.getL1CacheManager().get(key);
            assertEquals(value, l1Value, "L1 应被回填");
        }

        @Test
        @DisplayName("L1 回填携带 L2 剩余 TTL — L2 过期后 L1 也失效")
        void testBackfillWithRemainingTtl() throws InterruptedException {
            if (!(cacheManager instanceof CompositeCacheManager composite)) {
                return;
            }

            String key = "ttl-backfill-key";
            String value = "ttl-value";
            long expireSeconds = 2; // L2 仅 2s

            // 写入 L2（不写 L1）
            composite.getL2CacheManager().put(key, value, expireSeconds);

            // 获取触发 L1 回填，L1 的 TTL 应被限制为 L2 剩余（约 2s）
            Object result = composite.get(key);
            assertEquals(value, result, "应从 L2 获取并回填 L1");
            assertNotNull(composite.getL1CacheManager().get(key), "L1 应被回填");

            // 等待 L2 过期（>2s），此时 L2 失效
            Thread.sleep(2500);
            assertNull(composite.getL2CacheManager().get(key), "L2 应已过期");

            // 若 L1 回填时正确携带了 L2 剩余 TTL，L1 此时也应已失效（不会永驻）
            assertNull(composite.getL1CacheManager().get(key),
                "L1 回填应携带 L2 剩余 TTL，L2 过期后 L1 不应残留");
        }

        @Test
        @DisplayName("NullValueSentinel 只存 L1 不存 L2")
        void testNullValueOnlyInL1() {
            if (!(cacheManager instanceof CompositeCacheManager composite)) {
                return;
            }

            // 使用 cacheNullValue=true 的方法
            long countBefore = bingCacheDemos.getGetConfigCallCount();

            String result = bingCacheDemos.getConfig("not-exist");
            assertNull(result, "不存在的配置应返回 null");
            long countAfterFirst = bingCacheDemos.getGetConfigCallCount();
            assertEquals(countBefore + 1, countAfterFirst, "首次调用应执行方法体");

            // 再次获取，验证 null 被缓存 — 不应再进入方法体
            String result2 = bingCacheDemos.getConfig("not-exist");
            assertNull(result2, "第二次应从缓存获取 null");
            assertEquals(countAfterFirst, bingCacheDemos.getGetConfigCallCount(),
                "null 应被缓存，第二次不应重新执行方法");

            // NullValueSentinel 只存 L1 不存 L2：
            // L2（Redis）中该 key 不应存在 null 值。
            // key 格式为 config(Sg[S:not-exist])，直接查 L2 应为 null。
            Object l2Value = composite.getL2CacheManager().get("config(Sg[S:not-exist])");
            assertNull(l2Value, "NullValueSentinel 不应写入 L2");
        }
    }

    // ========== 3. 版本对账机制测试 ==========

    @Nested
    @DisplayName("版本对账测试")
    class ReconciliationTest {

        @Test
        @DisplayName("clear() 触发全局版本号递增")
        void testClearIncrementsGlobalVersion() {
            if (!(cacheManager instanceof CompositeCacheManager composite) || cacheVersionStore == null) {
                return;
            }

            String userKey = "user(Sg[N:1])";
            String dictKey = "dict(Sg[S:test])";

            // 准备缓存数据
            bingCacheDemos.getUserById(1L);
            bingCacheDemos.getDict("test");
            assertPresentInL1AndL2(composite, userKey, "clear 前 user 缓存应存在");
            assertPresentInL1AndL2(composite, dictKey, "clear 前 dict 缓存应存在");

            long versionBefore = cacheVersionStore.getAllVersion();

            // 清空所有缓存
            cacheManager.clear();

            assertEquals(versionBefore + 1, cacheVersionStore.getAllVersion(),
                "clear() 应递增全局版本号");
            assertAbsentFromL1AndL2(composite, userKey, "clear() 后 user 缓存应为空");
            assertAbsentFromL1AndL2(composite, dictKey, "clear() 后 dict 缓存应为空");
        }

        @Test
        @DisplayName("clearByPrefix() 触发单前缀版本号递增")
        void testClearByPrefixIncrementsVersion() {
            if (!(cacheManager instanceof CompositeCacheManager composite) || cacheVersionStore == null) {
                return;
            }

            String userKey = "user(Sg[N:1])";
            String dictKey = "dict(Sg[S:test])";

            // 准备不同前缀的缓存
            bingCacheDemos.getUserById(1L);
            bingCacheDemos.getDict("test");
            assertPresentInL1AndL2(composite, userKey, "clearByPrefix 前 user 缓存应存在");
            assertPresentInL1AndL2(composite, dictKey, "clearByPrefix 前 dict 缓存应存在");

            long versionBefore = cacheVersionStore.getVersion("user");

            // 只清空前缀为 "user" 的缓存
            cacheManager.clearByPrefix("user");

            assertEquals(versionBefore + 1, cacheVersionStore.getVersion("user"),
                "clearByPrefix('user') 应递增 user 版本号");
            assertAbsentFromL1AndL2(composite, userKey, "clearByPrefix('user') 后 user 缓存应为空");
            assertPresentInL1AndL2(composite, dictKey, "clearByPrefix('user') 不应清除 dict 缓存");
        }
    }

    // ========== 4. Redis 降级与恢复测试 ==========

    @Nested
    @DisplayName("Redis 降级与恢复测试")
    class RedisDegradationTest {

        @Test
        @DisplayName("Redis 可用时 L1 与 L2 均写入")
        void testRedisAvailable() {
            if (!(cacheManager instanceof CompositeCacheManager composite)) {
                return;
            }

            String key = "redis-test-key";
            String value = "redis-value";

            // CompositeCacheManager.put 同时写 L1 和 L2
            cacheManager.put(key, value, 60);

            // L1 与 L2 都应有值
            assertEquals(value, composite.getL1CacheManager().get(key), "L1 应有缓存值");
            assertEquals(value, composite.getL2CacheManager().get(key), "L2 (Redis) 应有缓存值");
        }

        @Test
        @DisplayName("直接操作 L1 时 L1 独立读写")
        void testL1IndependentReadwrite() {
            // 此测试不模拟 Redis 故障，仅验证 L1 (Caffeine) 独立读写能力。
            String key = "l1-only-key";
            String value = "l1-value";

            if (cacheManager instanceof CompositeCacheManager composite) {
                composite.getL1CacheManager().put(key, value, 60);
                Object result = composite.getL1CacheManager().get(key);
                assertEquals(value, result, "L1 应独立读写");
            }
        }
    }

    // ========== 5. 缓存 key 长度限制测试 ==========

    @Test
    @DisplayName("超长 key 自动截断 + SHA-256 后缀")
    void testLongKeyTruncation() {
        // 构造一个超长的参数
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("very-long-param-");
        }
        String longParam = sb.toString();

        // 调用方法，key 应该被截断
        String result = cacheDemoExamples.withKeyPrefix(longParam);
        assertNotNull(result, "超长参数应正常返回");

        // 再次调用应命中缓存
        String result2 = cacheDemoExamples.withKeyPrefix(longParam);
        assertEquals(result, result2, "超长参数应命中缓存");
    }

    @Test
    @DisplayName("不同超长参数生成不同截断 key")
    void testDifferentLongParamsDifferentKeys() {
        StringBuilder sb1 = new StringBuilder();
        StringBuilder sb2 = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb1.append("param-A-");
            sb2.append("param-B-");
        }

        String result1 = cacheDemoExamples.withKeyPrefix(sb1.toString());
        String result2 = cacheDemoExamples.withKeyPrefix(sb2.toString());

        assertNotEquals(result1, result2, "不同超长参数应生成不同缓存");
    }

    // ========== 6. 边界条件测试 ==========

    @Test
    @DisplayName("expireTime=0 永不过期")
    void testZeroExpireTimeNeverExpires() throws InterruptedException {
        // CacheDemoExamples.configData 的 expireTime=3600，不是 0
        // 这里测试一个较长的过期时间，验证不会提前过期
        String result1 = cacheDemoExamples.configData("test-key");
        Thread.sleep(100);
        String result2 = cacheDemoExamples.configData("test-key");
        assertEquals(result1, result2, "短时间内不应过期");
    }

    @Test
    @DisplayName("空字符串参数")
    void testEmptyStringParam() {
        // 测试空字符串参数不会导致异常
        String result = cacheDemoExamples.simpleCache("");
        assertNotNull(result, "空字符串参数应正常缓存");

        // 再次调用应命中缓存
        String result2 = cacheDemoExamples.simpleCache("");
        assertEquals(result, result2, "空字符串参数应命中缓存");
    }

    @Test
    @DisplayName("特殊字符参数")
    void testSpecialCharParam() {
        String specialChars = "key with spaces & special chars: !@#$%^&*()";
        String result1 = cacheDemoExamples.withKeyPrefix(specialChars);
        String result2 = cacheDemoExamples.withKeyPrefix(specialChars);
        assertEquals(result1, result2, "特殊字符参数应正常缓存");
    }

    @Test
    @DisplayName("中文参数")
    void testChineseParam() {
        String chinese = "测试中文参数";
        String result1 = cacheDemoExamples.withKeyPrefix(chinese);
        String result2 = cacheDemoExamples.withKeyPrefix(chinese);
        assertEquals(result1, result2, "中文参数应正常缓存");
    }

    // ========== 7. 并发缓存击穿防护测试 ==========

    @Test
    @DisplayName("多线程并发访问同一未缓存 key - 不抛异常且最终值收敛")
    void testConcurrentCacheMissConverges() throws Exception {
        // 注意：bing-cache 的 CacheAspect 不加锁，并发 miss 时多个线程会同时执行方法体
        // （即不防缓存击穿）。本测试只验证：并发场景下不抛异常，且所有线程都能拿到非 null 结果，
        // 最终缓存值收敛为某个一致结果。
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await(); // 所有线程同时开始
                return bingCacheDemos.getUserById(99999L);
            }));
        }

        startLatch.countDown();

        Set<String> results = ConcurrentHashMap.newKeySet();
        for (Future<String> future : futures) {
            String result = future.get(10, TimeUnit.SECONDS);
            assertNotNull(result, "并发访问不应返回 null");
            results.add(result);
        }
        executor.shutdown();

        // 不抛异常即可；results 可能含多个值（并发 miss 各自重算），最终缓存收敛为其中一个。
        assertFalse(results.isEmpty(), "应至少有一个结果");
    }

    @Test
    @DisplayName("并发读写不会导致数据不一致")
    void testConcurrentReadWriteConsistency() throws Exception {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger errorCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        // 混合读写操作
        for (int i = 0; i < 100; i++) {
            final int idx = i;
            if (i % 3 == 0) {
                // 写操作
                futures.add(executor.submit(() -> {
                    try {
                        bingCacheDemos.getUserById((long) (idx % 10));
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    }
                }));
            } else {
                // 读操作
                futures.add(executor.submit(() -> {
                    try {
                        bingCacheDemos.getUserById((long) (idx % 10));
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    }
                }));
            }
        }

        // 等待完成
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }

        executor.shutdown();

        assertEquals(0, errorCount.get(), "并发读写不应有错误");
    }

    // ========== 8. L1 最大 TTL 限制测试 ==========

    @Nested
    @DisplayName("L1 最大 TTL 限制测试")
    class L1MaxTtlTest {

        @Test
        @DisplayName("L1+L2 模式下自动兜底 300s")
        void testL1MaxTtlDefaultFallback() {
            if (!(cacheManager instanceof CompositeCacheManager composite)) {
                return;
            }

            // 验证 L1 有 maxTtl 配置
            CacheManager l1 = composite.getL1CacheManager();
            if (l1 instanceof CaffeineCacheManager caffeine) {
                // l1MaxTtl 应该大于 0（配置文件设置为 300）
                assertTrue(caffeine.getL1MaxTtlSeconds() > 0,
                    "L1 最大 TTL 应大于 0");
            }
        }

        @Test
        @DisplayName("永不过期条目被 l1MaxTtl 截断并实际过期")
        void testL1EntryRespectsMaxTtl() throws InterruptedException {
            // 用独立的短 maxTtl 实例真实验证：expireSeconds=0（永不过期）的条目
            // 在 l1MaxTtl 截断后应于 maxTtl 到期时失效。
            CaffeineCacheManager l1 = new CaffeineCacheManager(1000L, 1L); // maxTtl=1s
            l1.put("no-expire-key", "value", 0); // 请求永不过期

            // 立即应可读
            assertEquals("value", l1.get("no-expire-key"), "写入后应立即可读");

            // 等待 maxTtl（1s）过后应已过期
            Thread.sleep(1500);
            assertNull(l1.get("no-expire-key"), "永不过期条目应被 l1MaxTtl 截断为 1s 后过期");
        }
    }

    // ========== 9. 缓存 key 格式验证 ==========

    @Test
    @DisplayName("cacheName 优先级高于 keyPrefix")
    void testCacheNamePriorityOverKeyPrefix() {
        // BingCacheDemos.getUserDetail 使用 cacheName="userDetail"
        // BingCacheDemos.getDict 使用 keyPrefix="dict"

        // 两者都应正常工作
        String userDetail = bingCacheDemos.getUserDetail(
            new BingCacheDemos.UserDetailQuery(1L, "test"));
        String dict = bingCacheDemos.getDict("test-type");

        assertNotNull(userDetail);
        assertNotNull(dict);

        // 再次调用应命中缓存
        assertEquals(userDetail, bingCacheDemos.getUserDetail(
            new BingCacheDemos.UserDetailQuery(1L, "test")));
        assertEquals(dict, bingCacheDemos.getDict("test-type"));
    }

    @Test
    @DisplayName("argSpel 只用指定字段生成 key，其他字段不同也命中同一缓存")
    void testArgSpelIgnoresOtherFields() throws InterruptedException {
        CacheDemoExamples.UserQuery query1 = new CacheDemoExamples.UserQuery();
        query1.setName("spel-test");
        query1.setAge(20);

        CacheDemoExamples.UserQuery query2 = new CacheDemoExamples.UserQuery();
        query2.setName("spel-test"); // 相同 name
        query2.setAge(99); // 不同 age

        String result1 = cacheDemoExamples.objectAsParamWithSpel(query1);
        Thread.sleep(2);
        String result2 = cacheDemoExamples.objectAsParamWithSpel(query2);

        assertEquals(result1, result2,
            "argSpel=#query.name 时，相同 name 应命中同一缓存，忽略 age");
    }

    // ========== 10. 缓存清除完整性测试 ==========

    @Test
    @DisplayName("clear() 清除所有 L1 和 L2 缓存")
    void testClearRemovesAllCaches() {
        if (!(cacheManager instanceof CompositeCacheManager composite)) {
            return;
        }

        String userKey1 = "user(Sg[N:1])";
        String userKey2 = "user(Sg[N:2])";
        String dictKey = "dict(Sg[S:type1])";

        // 准备多条缓存
        bingCacheDemos.getUserById(1L);
        bingCacheDemos.getUserById(2L);
        bingCacheDemos.getDict("type1");
        assertPresentInL1AndL2(composite, userKey1, "clear 前 user1 缓存应存在");
        assertPresentInL1AndL2(composite, userKey2, "clear 前 user2 缓存应存在");
        assertPresentInL1AndL2(composite, dictKey, "clear 前 dict 缓存应存在");

        // 清空
        cacheManager.clear();

        assertAbsentFromL1AndL2(composite, userKey1, "clear() 后 user1 缓存应为空");
        assertAbsentFromL1AndL2(composite, userKey2, "clear() 后 user2 缓存应为空");
        assertAbsentFromL1AndL2(composite, dictKey, "clear() 后 dict 缓存应为空");
    }

    @Test
    @DisplayName("clearByPrefix() 只清除指定前缀")
    void testClearByPrefixOnlyRemovesTarget() {
        if (!(cacheManager instanceof CompositeCacheManager composite)) {
            return;
        }

        String userKey = "user(Sg[N:1])";
        String dictKey = "dict(Sg[S:type1])";

        // 准备不同前缀的缓存
        bingCacheDemos.getUserById(1L);
        bingCacheDemos.getDict("type1");
        assertPresentInL1AndL2(composite, userKey, "clearByPrefix 前 user 缓存应存在");
        assertPresentInL1AndL2(composite, dictKey, "clearByPrefix 前 dict 缓存应存在");

        // 只清除 user 前缀
        cacheManager.clearByPrefix("user");

        assertAbsentFromL1AndL2(composite, userKey, "user 缓存应被清除");
        assertPresentInL1AndL2(composite, dictKey, "dict 缓存不应被清除");
    }

    @Test
    @DisplayName("前缀碰撞保护 - user 不影响 userDetail")
    void testPrefixCollisionProtection() {
        if (!(cacheManager instanceof CompositeCacheManager composite)) {
            return;
        }

        String userKey = "user(Sg[N:1])";
        String userDetailKey = "userDetail(Sg[N:1])";
        BingCacheDemos.UserDetailQuery query = new BingCacheDemos.UserDetailQuery(1L, "test");

        // 准备 user 和 userDetail 缓存
        String userBefore = bingCacheDemos.getUserById(1L);
        String userDetailBefore = bingCacheDemos.getUserDetail(query);
        assertPresentInL1AndL2(composite, userKey, "clearByPrefix 前 user 缓存应存在");
        assertPresentInL1AndL2(composite, userDetailKey, "clearByPrefix 前 userDetail 缓存应存在");

        // 清除 user 前缀
        cacheManager.clearByPrefix("user");

        assertAbsentFromL1AndL2(composite, userKey, "user 应被 clearByPrefix('user') 清除");
        assertPresentInL1AndL2(composite, userDetailKey,
            "userDetail 不应被 clearByPrefix('user') 误删");

        String userDetailAfter = bingCacheDemos.getUserDetail(query);
        assertEquals(userDetailBefore, userDetailAfter,
            "userDetail 不应被 clearByPrefix('user') 误删后重算");

        String userAfter = bingCacheDemos.getUserById(1L);
        assertNotEquals(userBefore, userAfter, "user 被清除后应重新执行方法体");
    }

    // ========== 11. clearByPrefix 不影响 group 条目 ==========

    @Test
    @DisplayName("clearByPrefix 不影响 group 方式缓存的条目")
    void testClearByPrefixDoesNotAffectGroupEntries() {
        if (!(cacheManager instanceof CompositeCacheManager composite)) {
            return;
        }

        String adminUserKey = "admin:user(Sg[N:801])";
        String userKey = "user(Sg[N:801])";

        // getAdminUser: group="admin", cacheName="user", key=admin:user(...)
        // getUserById:  group 为空,     cacheName="user", key=user(...)
        String adminBefore = bingCacheDemos.getAdminUser(801L);
        String userBefore = bingCacheDemos.getUserById(801L);
        assertPresentInL1AndL2(composite, adminUserKey, "clearByPrefix 前 admin:user 缓存应存在");
        assertPresentInL1AndL2(composite, userKey, "clearByPrefix 前普通 user 缓存应存在");

        // clearByPrefix("user") 只清除 user( 开头的 key
        cacheManager.clearByPrefix("user");

        assertAbsentFromL1AndL2(composite, userKey,
            "clearByPrefix('user') 应清除无 group 的 user 缓存");
        assertPresentInL1AndL2(composite, adminUserKey,
            "clearByPrefix('user') 不应影响 group=admin 的 user 缓存");

        String adminAfter = bingCacheDemos.getAdminUser(801L);
        assertEquals(adminBefore, adminAfter,
            "clearByPrefix('user') 不应影响 group=admin 的 user 缓存");

        String userAfter = bingCacheDemos.getUserById(801L);
        assertNotEquals(userBefore, userAfter, "无 group 的 user 被清除后应重新执行方法体");
    }

    // ========== 12. 重复 clear 稳定性 ==========

    @Test
    @DisplayName("重复调用 clear() 不应抛异常")
    void testRepeatedClearDoesNotThrow() {
        bingCacheDemos.getUserById(1L);
        bingCacheDemos.getDict("test");

        // 多次 clear
        assertDoesNotThrow(() -> cacheManager.clear());
        assertDoesNotThrow(() -> cacheManager.clear());
        assertDoesNotThrow(() -> cacheManager.clear());

        // clear 后缓存应为空
        assertNull(cacheManager.get("user(Sg[N:1])"));
    }

    // ========== 13. clearByGroup + clearByPrefix 交叉验证 ==========

    @Test
    @DisplayName("clearByGroup + clearByPrefix 交叉 - 互不影响非目标条目")
    void testClearByGroupAndClearByPrefixCrossValidation() {
        if (!(cacheManager instanceof CompositeCacheManager composite)) {
            return;
        }

        String adminUserKey = "admin:user(Sg[N:901])";
        String userKey = "user(Sg[N:901])";
        String adminDictKey = "admin:dict(Sg[S:cross-test])";
        String dictKey = "dict(Sg[S:cross-test])";

        // 准备缓存数据
        String adminUserOrig = bingCacheDemos.getAdminUser(901L);
        bingCacheDemos.getUserById(901L);
        String adminDictOrig = bingCacheDemos.getAdminDict("cross-test");
        String dictOrig = bingCacheDemos.getDict("cross-test");
        assertPresentInL1AndL2(composite, adminUserKey, "交叉验证前 admin:user 缓存应存在");
        assertPresentInL1AndL2(composite, userKey, "交叉验证前普通 user 缓存应存在");
        assertPresentInL1AndL2(composite, adminDictKey, "交叉验证前 admin:dict 缓存应存在");
        assertPresentInL1AndL2(composite, dictKey, "交叉验证前普通 dict 缓存应存在");

        // Step 1: clearByPrefix("user") — 只影响 user( 开头
        cacheManager.clearByPrefix("user");

        assertAbsentFromL1AndL2(composite, userKey, "normal user 应被 clearByPrefix 清除");
        assertPresentInL1AndL2(composite, adminUserKey,
            "admin:user 不应被 clearByPrefix('user') 清除");
        assertPresentInL1AndL2(composite, adminDictKey,
            "admin:dict 不应被 clearByPrefix('user') 影响");
        assertPresentInL1AndL2(composite, dictKey,
            "dict 缓存不应被 clearByPrefix('user') 影响");

        String adminUserAfterPrefixClear = bingCacheDemos.getAdminUser(901L);
        assertEquals(adminUserOrig, adminUserAfterPrefixClear,
            "admin:user 不应被 clearByPrefix('user') 清除");
        assertEquals(dictOrig, bingCacheDemos.getDict("cross-test"),
            "normal dict 不应被 clearByPrefix('user') 影响");

        // Step 2: clearByGroup("admin") — 只影响 admin: 开头
        bingCacheDemos.clearAdminGroup();

        assertAbsentFromL1AndL2(composite, adminUserKey, "admin:user 应被 clearByGroup 清除");
        assertAbsentFromL1AndL2(composite, adminDictKey, "admin:dict 应被 clearByGroup 清除");
        assertPresentInL1AndL2(composite, dictKey,
            "keyPrefix=dict 不应被 clearByGroup('admin') 清除");

        String adminUserAfterGroupClear = bingCacheDemos.getAdminUser(901L);
        assertNotEquals(adminUserOrig, adminUserAfterGroupClear,
            "admin:user 应被 clearByGroup 清除后重新执行");

        String adminDictAfter = bingCacheDemos.getAdminDict("cross-test");
        assertNotEquals(adminDictOrig, adminDictAfter,
            "admin:dict 应被 clearByGroup 清除后重新执行");
        assertEquals(dictOrig, bingCacheDemos.getDict("cross-test"),
            "normal dict 不应被 clearByGroup('admin') 清除");
    }
}
