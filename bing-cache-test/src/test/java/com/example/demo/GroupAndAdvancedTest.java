package com.example.demo;

import com.bing.cache.cache.CacheManager;
import com.bing.cache.cache.CompositeCacheManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * group 分组、全局清除、保留名校验等高级场景测试.
 *
 * <p>覆盖以下场景：
 * <ol>
 *   <li>group + cacheName 组合前缀的缓存与清除</li>
 *   <li>clearByGroup 分组隔离 — 清除一个分组不影响另一个分组</li>
 *   <li>allEntries=true 无前缀 — 全局清空</li>
 *   <li>@BingCacheEvict argSpel 路径 — SpEL 配对失效</li>
 *   <li>保留名校验 — group/cacheName 使用内部保留名时抛异常</li>
 * </ol>
 */
@SpringBootTest
public class GroupAndAdvancedTest {

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private BingCacheDemos bingCacheDemos;

    @BeforeEach
    void clearCache() {
        cacheManager.clear();
    }

    // ========== 1. group + cacheName 组合前缀 ==========

    @Test
    @DisplayName("group + cacheName 缓存命中")
    void testGroupCacheHit() {
        Long userId = 101L;

        String first = bingCacheDemos.getAdminUser(userId);
        String second = bingCacheDemos.getAdminUser(userId);

        assertEquals(first, second, "相同参数应命中缓存");
        assertTrue(first.contains("AdminUser-" + userId));
    }

    @Test
    @DisplayName("不同 group 下相同 cacheName+args 缓存隔离")
    void testDifferentGroupCacheIsolation() {
        // getAdminUser: group="admin", cacheName="user"
        // getUserById:  group 为空,  cacheName="user"
        // 两者 cacheName 相同但 group 不同，缓存应隔离
        Long userId = 201L;

        String adminResult = bingCacheDemos.getAdminUser(userId);
        String normalResult = bingCacheDemos.getUserById(userId);

        assertNotEquals(adminResult, normalResult,
            "不同 group 下的同 cacheName 缓存应隔离");
        assertTrue(adminResult.contains("AdminUser"));
        assertTrue(normalResult.contains("User-"));
    }

    // ========== 2. clearByGroup 分组隔离 ==========

    @Test
    @DisplayName("clearByGroup 只清除目标分组，不影响其他分组")
    void testClearByGroupIsolation() throws InterruptedException {
        // 准备 admin 分组缓存
        String adminUser = bingCacheDemos.getAdminUser(301L);
        String adminDict = bingCacheDemos.getAdminDict("status");
        // 准备无分组缓存（普通 user）
        String normalUser = bingCacheDemos.getUserById(301L);
        // 准备字典缓存（keyPrefix="dict"，无 group）
        String normalDict = bingCacheDemos.getDict("status");
        Thread.sleep(2);

        // 清除 admin 分组
        bingCacheDemos.clearAdminGroup();

        // admin 分组应被清除 → 重新执行
        String adminUserAfter = bingCacheDemos.getAdminUser(301L);
        String adminDictAfter = bingCacheDemos.getAdminDict("status");
        assertNotEquals(adminUser, adminUserAfter, "admin:user 应被 clearByGroup 清除");
        assertNotEquals(adminDict, adminDictAfter, "admin:dict 应被 clearByGroup 清除");

        // 无分组缓存应保留
        String normalUserAfter = bingCacheDemos.getUserById(301L);
        String normalDictAfter = bingCacheDemos.getDict("status");
        assertEquals(normalUser, normalUserAfter, "无分组的 user 缓存不应受影响");
        assertEquals(normalDict, normalDictAfter, "无分组的 dict 缓存不应受影响");
    }

    @Test
    @DisplayName("clearByGroup 不同分组互不影响")
    void testClearByGroupDoesNotAffectOtherGroup() throws InterruptedException {
        // 准备 admin 分组缓存
        String admin = bingCacheDemos.getAdminUser(401L);
        // 准备 portal 分组缓存 — 复用 getDict 作为 portal 分组的对比
        // portal 分组在本测试中没有 @BingCache 方法，但 clearPortalGroup 不应影响 admin
        Thread.sleep(2);

        // 清除 portal 分组（portal 下无缓存数据，但不应影响 admin）
        bingCacheDemos.clearPortalGroup();

        String adminAfter = bingCacheDemos.getAdminUser(401L);
        assertEquals(admin, adminAfter, "清除 portal 分组不应影响 admin 分组");
    }

    // ========== 3. allEntries=true 无前缀 → 全局清空 ==========

    @Test
    @DisplayName("allEntries=true 不指定前缀 → 全局清空所有缓存")
    void testEvictAllEntriesNoPrefixClearsAll() throws InterruptedException {
        // 准备多种缓存
        String adminUser = bingCacheDemos.getAdminUser(501L);
        String normalUser = bingCacheDemos.getUserById(501L);
        String dict = bingCacheDemos.getDict("global-test");
        Thread.sleep(2);

        // 全局清空
        bingCacheDemos.clearAll();

        // 所有缓存都应被清除
        assertNotEquals(adminUser, bingCacheDemos.getAdminUser(501L),
            "全局清空后 admin user 应重新执行");
        assertNotEquals(normalUser, bingCacheDemos.getUserById(501L),
            "全局清空后 normal user 应重新执行");
        assertNotEquals(dict, bingCacheDemos.getDict("global-test"),
            "全局清空后 dict 应重新执行");
    }

    // ========== 4. @BingCacheEvict argSpel 路径 ==========

    @Test
    @DisplayName("@BingCacheEvict argSpel 配对失效 — getUserDetail/updateUserDetail")
    void testEvictByArgSpel() throws InterruptedException {
        BingCacheDemos.UserDetailQuery query = new BingCacheDemos.UserDetailQuery(601L, "web");

        String before = bingCacheDemos.getUserDetail(query);
        Thread.sleep(2);

        // updateUserDetail 使用 argSpel=#query.id，应清除 getUserDetail 的缓存
        bingCacheDemos.updateUserDetail(query, "updated-name");

        String after = bingCacheDemos.getUserDetail(query);
        assertNotEquals(before, after, "argSpel 配对失效后应重新执行");
    }

    @Test
    @DisplayName("@BingCacheEvict argSpel — 不同 source 相同 id 应命中同一缓存")
    void testArgSpelSameIdDifferentSource() throws InterruptedException {
        BingCacheDemos.UserDetailQuery q1 = new BingCacheDemos.UserDetailQuery(701L, "web");
        BingCacheDemos.UserDetailQuery q2 = new BingCacheDemos.UserDetailQuery(701L, "app");

        String r1 = bingCacheDemos.getUserDetail(q1);
        Thread.sleep(2);
        String r2 = bingCacheDemos.getUserDetail(q2);

        assertEquals(r1, r2, "argSpel=#query.id 时，相同 id 不同 source 应命中同一缓存");
    }

    // ========== 5. 保留名校验 ==========

    @Nested
    @DisplayName("保留名校验 — 调用时抛 IllegalStateException")
    class ReservedNameValidationTest {

        @Test
        @DisplayName("group='__version__' 应被拒绝")
        void testReservedGroupVersion() {
            assertThrows(IllegalStateException.class,
                bingCacheDemos::clearWithReservedGroup,
                "group='__version__' 是保留名，应抛 IllegalStateException");
        }

        @Test
        @DisplayName("cacheName='__all__' 应被拒绝")
        void testReservedCacheNameAll() {
            assertThrows(IllegalStateException.class,
                bingCacheDemos::clearWithReservedCacheName,
                "cacheName='__all__' 是保留名，应抛 IllegalStateException");
        }

        @Test
        @DisplayName("cacheName='__group__:user' 应被拒绝")
        void testReservedCacheNameGroupPrefix() {
            assertThrows(IllegalStateException.class,
                bingCacheDemos::clearWithReservedCacheNamePrefix,
                "cacheName 以 '__group__:' 开头是保留前缀，应抛 IllegalStateException");
        }
    }
}
