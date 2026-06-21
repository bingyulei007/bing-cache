package com.example.demo;

import com.bing.cache.cache.CacheManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * BingCache 核心功能演示接口
 *
 * 测试步骤：
 * 1. 先调用查询接口（如 /demo/user/1）缓存数据
 * 2. 再调用更新接口（如 /demo/user/update/1）清除缓存
 * 3. 再次查询，验证缓存已被清除
 */
@RestController
@RequestMapping("/demo")
public class BingCacheDemoController {

    @Autowired
    private BingCacheDemos bingCacheDemos;

    @Autowired
    private CacheManager cacheManager;

    // ==================== 场景1: 基础缓存 + 清除 ====================

    /**
     * 查询用户 - 先调用这个缓存数据
     * GET /demo/user/{id}
     */
    @GetMapping("/user/{id}")
    public Map<String, Object> getUser(@PathVariable Long id) {
        long t1 = System.nanoTime();
        String result = bingCacheDemos.getUserById(id);
        long t2 = System.nanoTime();

        Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("data", result);
        map.put("costMs", String.format("%.3f", (t2 - t1) / 1_000_000.0));
        map.put("timestamp", System.currentTimeMillis());
        return map;
    }

    /**
     * 更新用户 - 清除缓存
     * POST /demo/user/update/{id}?name=xxx
     *
     * 调用后再查询 /demo/user/{id}，会发现重新执行了方法（缓存被清除）
     */
    @PostMapping("/user/update/{id}")
    public Map<String, Object> updateUser(@PathVariable Long id, @RequestParam String name) {
        bingCacheDemos.updateUser(id, name);

        Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("newName", name);
        map.put("message", "缓存已清除，再次查询会重新执行方法");
        return map;
    }

    // ==================== 场景2: argIndexes 演示 ====================

    /**
     * 分页查询用户列表
     * GET /demo/users?category=vip&keyword=xxx&page=1
     *
     * 注意: keyword 不参与缓存 key，所以相同 category+page 不同 keyword 共享缓存
     */
    @GetMapping("/users")
    public Map<String, Object> queryUsers(
            @RequestParam String category,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "1") Integer page) {

        long t1 = System.nanoTime();
        String result = bingCacheDemos.queryUsers(category, keyword, page);
        long t2 = System.nanoTime();

        Map<String, Object> map = new HashMap<>();
        map.put("params", Map.of("category", category, "keyword", keyword, "page", page));
        map.put("result", result);
        map.put("costMs", String.format("%.3f", (t2 - t1) / 1_000_000.0));
        return map;
    }

    /**
     * 清除用户列表缓存 - 按 category+page 精确清除
     * POST /demo/users/clear?category=vip&keyword=xxx&page=1
     */
    @PostMapping("/users/clear")
    public Map<String, Object> clearUserListCache(
            @RequestParam String category,
            @RequestParam String keyword,
            @RequestParam Integer page) {
        bingCacheDemos.clearUserListCache(category, keyword, page);
        return Map.of("message", "已清除 category=" + category + " page=" + page + " 的缓存");
    }

    /**
     * 清除某分类下的所有缓存
     * POST /demo/users/clear/category/{category}
     */
    @PostMapping("/users/clear/category/{category}")
    public Map<String, Object> clearCategoryCache(@PathVariable String category) {
        bingCacheDemos.clearCategoryCache(category, "anyKeyword");
        return Map.of("message", "已清除 category=" + category + " 下的所有用户列表缓存");
    }

    // ==================== 场景3: 缓存穿透防护 ====================

    /**
     * 查询配置 - 演示 cacheNullValue=true
     * GET /demo/config/{key}
     * 特殊: key=not-exist 会返回 null，但 null 也会被缓存
     */
    @GetMapping("/config/{key}")
    public Map<String, Object> getConfig(@PathVariable String key) {
        long t1 = System.nanoTime();
        String result = bingCacheDemos.getConfig(key);
        long t2 = System.nanoTime();

        Map<String, Object> map = new HashMap<>();
        map.put("key", key);
        map.put("value", result);
        map.put("isNull", result == null);
        map.put("costMs", String.format("%.3f", (t2 - t1) / 1_000_000.0));
        map.put("note", "如果是 null，说明 null 值被缓存了（防止穿透）");
        return map;
    }

    // ==================== 场景4: keyPrefix ====================

    /**
     * 查询字典
     * GET /demo/dict/{type}
     */
    @GetMapping("/dict/{type}")
    public Map<String, Object> getDict(@PathVariable String type) {
        String result = bingCacheDemos.getDict(type);
        return Map.of("type", type, "data", result);
    }

    /**
     * 更新字典
     * POST /demo/dict/update/{type}?value=xxx
     */
    @PostMapping("/dict/update/{type}")
    public Map<String, Object> updateDict(@PathVariable String type, @RequestParam String value) {
        bingCacheDemos.updateDict(type, value);
        return Map.of("message", "字典 " + type + " 已更新，缓存已清除");
    }

    // ==================== 场景5: 批量清除 ====================

    /**
     * 刷新所有用户缓存
     * POST /demo/user/refresh-all
     */
    @PostMapping("/user/refresh-all")
    public Map<String, Object> refreshAllUsers() {
        bingCacheDemos.refreshAllUsers();
        return Map.of("message", "所有 user 缓存已清除");
    }

    /**
     * 刷新所有字典缓存
     * POST /demo/dict/refresh-all
     */
    @PostMapping("/dict/refresh-all")
    public Map<String, Object> refreshAllDicts() {
        bingCacheDemos.refreshAllDicts();
        return Map.of("message", "所有字典缓存已清除");
    }

    // ==================== 场景6: beforeInvocation ====================

    /**
     * 强制刷新配置 - 演示 beforeInvocation=true
     * 即使方法抛异常，缓存也会被清除
     * POST /demo/config/force-refresh/{key}
     */
    @PostMapping("/config/force-refresh/{key}")
    public Map<String, Object> forceRefreshConfig(@PathVariable String key) {
        try {
            bingCacheDemos.forceRefreshConfig(key);
            return Map.of("message", "配置已更新");
        } catch (Exception e) {
            Map<String, Object> map = new HashMap<>();
            map.put("message", "更新失败但缓存已清除");
            map.put("exception", e.getMessage());
            return map;
        }
    }

    // ==================== 手动管理缓存 ====================

    /**
     * 手动清除指定 key 的缓存
     * DELETE /demo/cache?key=user([1])
     */
    @DeleteMapping("/cache")
    public Map<String, Object> manualEvict(@RequestParam String key) {
        cacheManager.evict(key);
        return Map.of("message", "已手动清除缓存: " + key);
    }

    /**
     * 手动按前缀清除缓存
     * DELETE /demo/cache/prefix/{prefix}
     */
    @DeleteMapping("/cache/prefix/{prefix}")
    public Map<String, Object> manualClearByPrefix(@PathVariable String prefix) {
        cacheManager.clearByPrefix(prefix);
        return Map.of("message", "已按前缀清除缓存: " + prefix);
    }

    /**
     * 清空所有缓存
     * DELETE /demo/cache/all
     */
    @DeleteMapping("/cache/all")
    public Map<String, Object> manualClearAll() {
        cacheManager.clear();
        return Map.of("message", "已清空所有缓存");
    }

    // ==================== 测试指南 ====================

    /**
     * 测试指南
     * GET /demo/guide
     */
    @GetMapping("/guide")
    public Map<String, Object> guide() {
        Map<String, Object> map = new HashMap<>();
        map.put("title", "BingCache 测试指南");

        map.put("场景1-缓存与清除", Map.of(
                "step1", "GET /demo/user/1 - 查询用户，缓存结果",
                "step2", "GET /demo/user/1 - 再次查询，应该更快（缓存命中）",
                "step3", "POST /demo/user/update/1?name=Tom - 更新用户，清除缓存",
                "step4", "GET /demo/user/1 - 再次查询，应该重新执行（缓存被清除）"
        ));

        map.put("场景2-argIndexes", Map.of(
                "说明", "keyword 不参与缓存 key 生成",
                "step1", "GET /demo/users?category=vip&keyword=a&page=1",
                "step2", "GET /demo/users?category=vip&keyword=b&page=1 - 复用缓存（因 keyword 不参与 key）",
                "step3", "POST /demo/users/clear?category=vip&keyword=any&page=1 - 精确清除"
        ));

        map.put("场景3-缓存穿透", Map.of(
                "step1", "GET /demo/config/not-exist - 返回 null，null 也会被缓存",
                "step2", "再次调用 /demo/config/not-exist - 直接返回缓存的 null，不执行方法"
        ));

        map.put("场景4-keyPrefix", Map.of(
                "说明", "keyPrefix 用于简化缓存 key 前缀",
                "step1", "GET /demo/dict/sys_config - 查询字典",
                "step2", "POST /demo/dict/update/sys_config?value=xxx - 更新并清除缓存"
        ));

        map.put("场景5-批量清除", Map.of(
                "step1", "POST /demo/user/refresh-all - 清除所有 user 前缀的缓存",
                "step2", "POST /demo/dict/refresh-all - 清除所有 dict 前缀的缓存"
        ));

        map.put("场景6-beforeInvocation", Map.of(
                "说明", "beforeInvocation=true 时，方法执行前就清除缓存",
                "step1", "POST /demo/config/force-refresh/test - 即使失败，缓存也会被清除"
        ));

        return map;
    }
}
