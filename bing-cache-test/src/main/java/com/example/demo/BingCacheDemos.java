package com.example.demo;

import com.bing.cache.annotation.BingCache;
import com.bing.cache.annotation.BingCacheEvict;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * BingCache 核心用法演示
 *
 * 包含以下场景：
 * 1. 基础缓存 - 缓存查询结果
 * 2. 缓存清除 - @BingCacheEvict 配对使用
 * 3. argIndexes - 多参数时指定哪些参数参与生成 key
 * 4. 缓存穿透 - cacheNullValue = true
 * 5. 不同过期时间 - 根据数据特性设置
 */
@Component
public class BingCacheDemos {

    /**
     * 方法调用序号（单调递增）.
     *
     * <p>演示方法的返回值携带此序号，用于在测试中判断方法是否被真正执行：
     * 命中缓存时序号不变（返回值相同），未命中或驱逐后重算时序号递增（返回值不同）。</p>
     *
     * <p>不用 {@link System#currentTimeMillis()}：Windows 上其粒度约 15ms，
     * 在延迟较低的本地 Redis 环境下，连续两次调用可能落入同一毫秒桶，
     * 导致"驱逐后应重新执行"的断言偶发失败。调用序号无此问题。</p>
     */
    private final AtomicLong callSeq = new AtomicLong();

    /**
     * getConfig 方法体执行计数，用于测试验证 cacheNullValue 与 beforeInvocation 行为.
     */
    private final AtomicLong getConfigCallCount = new AtomicLong();

    public long getGetConfigCallCount() {
        return getConfigCallCount.get();
    }

    /**
     * 返回并递增调用序号，供演示方法在返回值中携带.
     */
    private long nextCallSeq() {
        return callSeq.incrementAndGet();
    }

    // ==================== 场景1: 基础缓存 ====================

    /**
     * 查询用户信息 - 缓存 5 分钟
     *
     * key 格式: user([1])  其中 1 是参数值
     */
    @BingCache(cacheName = "user", expireTime = 300)
    public String getUserById(Long id) {
        return "User-" + id + " [time:" + nextCallSeq() + "]";
    }

    /**
     * 更新用户信息 - 清除对应缓存
     *
     * 注意: argIndexes = {0} 确保只用 id 生成 key
     * 这样生成的 key 和 getUserById 一致: user([1])
     */
    @BingCacheEvict(cacheName = "user", argIndexes = {0})
    public void updateUser(Long id, String name) {
        System.out.println("[DB] 更新用户 " + id + " 的名字为 " + name);
    }

    // ==================== 场景2: argIndexes 演示 ====================

    /**
     * 分页查询用户列表 - 只用 category 和 page 生成 key，keyword 不参与
     *
     * key 格式: userList([electronics, 1])  不包含 keyword
     */
    @BingCache(cacheName = "userList", argIndexes = {0, 2}, expireTime = 120)
    public String queryUsers(String category, String keyword, Integer page) {
        return "Query[" + category + "," + keyword + ",page=" + page + "]";
    }

    /**
     * 清除用户列表缓存 - argIndexes 必须和查询方法一致
     */
    @BingCacheEvict(cacheName = "userList", argIndexes = {0, 2})
    public void clearUserListCache(String category, String keyword, Integer page) {
        System.out.println("[Evict] 清除 userList 缓存");
    }

    /**
     * 清除某分类下的所有用户列表缓存
     */
    @BingCacheEvict(cacheName = "userList", argIndexes = {0})
    public void clearCategoryCache(String category, String anyParam) {
        System.out.println("[Evict] 清除 category=" + category + " 下的所有缓存");
    }

    // ==================== 场景3: 缓存穿透防护 ====================

    /**
     * 查询配置 - 缓存 null 结果，防止穿透
     *
     * 当 configKey 不存在时返回 null，null 也会被缓存
     * 下次查询直接返回缓存的 null，不会打到数据库
     */
    @BingCache(cacheName = "config", expireTime = 600, cacheNullValue = true)
    public String getConfig(String configKey) {
        getConfigCallCount.incrementAndGet();
        if ("not-exist".equals(configKey)) {
            return null; // 不存在的配置
        }
        return "ConfigValue:" + configKey;
    }

    // ==================== 场景4: keyPrefix 简化前缀 ====================

    /**
     * 字典数据 - 使用 keyPrefix 缩短 key 前缀
     *
     * 如果用默认前缀会是: com.example.demo.BingCacheDemos.getDict
     * 使用 keyPrefix 后: dict
     */
    @BingCache(keyPrefix = "dict", expireTime = 3600)
    public String getDict(String dictType) {
        return "Dict[" + dictType + "] [time:" + nextCallSeq() + "]";
    }

    /**
     * 更新字典 - keyPrefix 配对清除
     */
    @BingCacheEvict(keyPrefix = "dict", argIndexes = {0})
    public void updateDict(String dictType, String value) {
        System.out.println("[DB] 更新字典 " + dictType);
    }

    /**
     * 查询用户详情 - 使用 SpEL 从对象参数取 id 生成 key
     */
    @BingCache(cacheName = "userDetail", argSpel = "#query.id", expireTime = 300)
    public String getUserDetail(UserDetailQuery query) {
        return "UserDetail-" + query.getId() + "(" + query.getSource() + ") [time:" + nextCallSeq() + "]";
    }

    /**
     * 更新用户详情 - 使用相同 SpEL 与 getUserDetail 配对清除
     */
    @BingCacheEvict(cacheName = "userDetail", argSpel = "#query.id")
    public void updateUserDetail(UserDetailQuery query, String name) {
        System.out.println("[DB] 更新用户详情 " + query.getId() + " 的名字为 " + name);
    }

    // ==================== 场景5: allEntries 批量清除 ====================

    /**
     * 刷新所有用户相关的缓存
     */
    @BingCacheEvict(cacheName = "user", allEntries = true)
    public void refreshAllUsers() {
        System.out.println("[Evict] 刷新所有用户缓存");
    }

    /**
     * 刷新所有字典缓存
     */
    @BingCacheEvict(keyPrefix = "dict", allEntries = true)
    public void refreshAllDicts() {
        System.out.println("[Evict] 刷新所有字典缓存");
    }

    // ==================== 场景6: 重载方法默认前缀隔离 ====================

  /**
   * 按 ID 查询（Long 类型）- 使用默认前缀
   *
   * 不设置 cacheName 和 keyPrefix，使用默认前缀:
   * com.example.demo.BingCacheDemos.findItem(java.lang.Long)
   * 与 findItem(String) 参数类型不同，默认前缀不同，缓存隔离
   */
  @BingCache(expireTime = 120)
  public String findItem(Long id) {
    return "Item-Long:" + id + " [time:" + nextCallSeq() + "]";
  }

  /**
   * 按编码查询（String 类型）- 使用默认前缀
   *
   * 不设置 cacheName 和 keyPrefix，使用默认前缀:
   * com.example.demo.BingCacheDemos.findItem(java.lang.String)
   * 与 findItem(Long) 参数类型不同，默认前缀不同，缓存隔离
   */
  @BingCache(expireTime = 120)
  public String findItem(String code) {
    return "Item-String:" + code + " [time:" + nextCallSeq() + "]";
  }

  // ==================== 场景7: 多缓存协同失效 ====================

    /**
     * 用户详情 — 按 id 缓存
     *
     * key 格式: userAccount([1])
     */
    @BingCache(cacheName = "userAccount", expireTime = 300)
    public String getUserAccount(Long id) {
        return "Account-" + id + " [time:" + nextCallSeq() + "]";
    }

    /**
     * 用户订单列表 — 按 userId 缓存
     *
     * key 格式: userOrders([1])
     */
    @BingCache(cacheName = "userOrders", expireTime = 120)
    public String getUserOrders(Long userId) {
        return "Orders-" + userId + " [time:" + nextCallSeq() + "]";
    }

    /**
     * 用户统计数据 — 全局缓存
     *
     * key 格式: userStats([])
     */
    @BingCache(cacheName = "userStats", expireTime = 600)
    public String getUserStats() {
        return "Stats [time:" + nextCallSeq() + "]";
    }

    /**
     * 更新用户账号 — 需要同时清除账号详情和订单列表
     *
     * 多个 @BingCacheEvict 协同清除：
     * - userAccount: 清除该用户的账号详情
     * - userOrders: 清除该用户的订单列表
     */
    @BingCacheEvict(cacheName = "userAccount", argIndexes = {0})
    @BingCacheEvict(cacheName = "userOrders", argIndexes = {0})
    public void updateUserAccount(Long id, String name) {
        System.out.println("[DB] 更新用户账号 " + id + " 的名字为 " + name);
    }

    /**
     * 新增订单 — 只需清除订单列表，无需清除账号详情
     */
    @BingCacheEvict(cacheName = "userOrders", argIndexes = {0})
    public void createOrder(Long userId, String orderId) {
        System.out.println("[DB] 为用户 " + userId + " 创建订单 " + orderId);
    }

    /**
     * 刷新统计数据 — 只清除统计缓存
     */
    @BingCacheEvict(cacheName = "userStats", allEntries = true)
    public void refreshUserStats() {
        System.out.println("[DB] 刷新用户统计");
    }

  // ==================== 场景8: beforeInvocation ====================

    /**
     * 强制刷新配置 - 方法执行前就清除缓存
     * 场景: 即使更新失败，也要让后续请求查到最新数据
     */
    @BingCacheEvict(cacheName = "config", beforeInvocation = true)
    public void forceRefreshConfig(String configKey) {
        throw new RuntimeException("模拟更新失败");
    }

  // ==================== 场景9: group 分组缓存 ====================

    /**
     * 管理后台用户查询 — 归属 "admin" 分组.
     *
     * <p>key 格式: admin:user(Sg[N:1])</p>
     */
    @BingCache(group = "admin", cacheName = "user", expireTime = 300)
    public String getAdminUser(Long id) {
        return "AdminUser-" + id + " [time:" + nextCallSeq() + "]";
    }

    /**
     * 管理后台字典查询 — 归属 "admin" 分组.
     *
     * <p>key 格式: admin:dict(Sg[S:type1])</p>
     */
    @BingCache(group = "admin", cacheName = "dict", expireTime = 3600)
    public String getAdminDict(String dictType) {
        return "AdminDict[" + dictType + "] [time:" + nextCallSeq() + "]";
    }

    /**
     * 清除 admin 分组下所有缓存 — 一个注解清除 admin:user 和 admin:dict.
     */
    @BingCacheEvict(group = "admin", allEntries = true)
    public void clearAdminGroup() {
        System.out.println("[Evict] 清除 admin 分组所有缓存");
    }

    /**
     * 清除 portal 分组下所有缓存（portal 下无 @BingCache 方法，用于测试分组隔离）.
     */
    @BingCacheEvict(group = "portal", allEntries = true)
    public void clearPortalGroup() {
        System.out.println("[Evict] 清除 portal 分组所有缓存");
    }

    /**
     * 全局清空所有缓存 — 不指定 group/cacheName/keyPrefix.
     */
    @BingCacheEvict(allEntries = true)
    public void clearAll() {
        System.out.println("[Evict] 全局清空所有缓存");
    }

    // ==================== 场景10: 保留名校验（应抛异常） ====================

    /**
     * 使用保留 group 名 — 应抛 IllegalStateException.
     */
    @BingCacheEvict(group = "__version__", allEntries = true)
    public void clearWithReservedGroup() {
        System.out.println("[Evict] 不应执行到此");
    }

    /**
     * 使用保留 cacheName — 应抛 IllegalStateException.
     */
    @BingCacheEvict(cacheName = "__all__", allEntries = true)
    public void clearWithReservedCacheName() {
        System.out.println("[Evict] 不应执行到此");
    }

    /**
     * 使用保留 cacheName 前缀 — 应抛 IllegalStateException.
     */
    @BingCacheEvict(cacheName = "__group__:user", allEntries = true)
    public void clearWithReservedCacheNamePrefix() {
        System.out.println("[Evict] 不应执行到此");
    }

    public static class UserDetailQuery {
        private Long id;
        private String source;

        public UserDetailQuery(Long id, String source) {
            this.id = id;
            this.source = source;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
    }
}
