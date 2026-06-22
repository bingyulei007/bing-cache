package com.example.demo;

import com.bing.cache.annotation.BingCache;
import com.bing.cache.annotation.BingCacheEvict;
import org.springframework.stereotype.Component;

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

    // ==================== 场景1: 基础缓存 ====================

    /**
     * 查询用户信息 - 缓存 5 分钟
     *
     * key 格式: user([1])  其中 1 是参数值
     */
    @BingCache(cacheName = "user", expireTime = 300)
    public String getUserById(Long id) {
        return "User-" + id + " [time:" + System.currentTimeMillis() + "]";
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
        return "Dict[" + dictType + "] [time:" + System.currentTimeMillis() + "]";
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
        return "UserDetail-" + query.getId() + "(" + query.getSource() + ") [time:" + System.currentTimeMillis() + "]";
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
   * 默认前缀: com.example.demo.BingCacheDemos.findItem(java.lang.Long)
   * 与 findItem(String) 参数类型不同，默认前缀不同，缓存隔离
   */
  @BingCache(cacheName = "item", expireTime = 120)
  public String findItem(Long id) {
    return "Item-Long:" + id + " [time:" + System.currentTimeMillis() + "]";
  }

  /**
   * 按编码查询（String 类型）- 使用默认前缀
   *
   * 默认前缀: com.example.demo.BingCacheDemos.findItem(java.lang.String)
   * 与 findItem(Long) 参数类型不同，默认前缀不同，缓存隔离
   */
  @BingCache(cacheName = "item", expireTime = 120)
  public String findItem(String code) {
    return "Item-String:" + code + " [time:" + System.currentTimeMillis() + "]";
  }

  // ==================== 场景7: beforeInvocation ====================

    /**
     * 强制刷新配置 - 方法执行前就清除缓存
     * 场景: 即使更新失败，也要让后续请求查到最新数据
     */
    @BingCacheEvict(cacheName = "config", beforeInvocation = true)
    public void forceRefreshConfig(String configKey) {
        throw new RuntimeException("模拟更新失败");
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
