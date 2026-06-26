package com.example.demo;

import com.bing.cache.annotation.BingCache;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * BingCache 注解用法演示
 * 展示各种缓存配置组合
 */
@Component
public class CacheDemoExamples {

    /**
     * 方法体执行计数，用于测试区分"命中缓存"与"重新执行方法"。
     * 命中缓存不会进入方法体，计数不增长；未命中或驱逐后重算时计数递增。
     */
    private final AtomicLong cacheNullValueCallCount = new AtomicLong();
    private final AtomicLong noCacheNullValueCallCount = new AtomicLong();
    private final AtomicLong listDataCallCount = new AtomicLong();

    public long getCacheNullValueCallCount() {
        return cacheNullValueCallCount.get();
    }

    public long getNoCacheNullValueCallCount() {
        return noCacheNullValueCallCount.get();
    }

    public long getListDataCallCount() {
        return listDataCallCount.get();
    }

    // ========== 基础缓存用法 ==========

    /**
     * 1. 最基础用法 - 默认配置
     * - cacheName: 缓存名称空间
     * - expireTime: 过期时间(秒)
     */
    @BingCache(cacheName = "simple", expireTime = 60)
    public String simpleCache(String input) {
        return "Result: " + input;
    }

    // ========== keyPrefix 缓存 ==========

    /**
     * 2. 使用 keyPrefix 自定义缓存key前缀
     * 生成的key格式: keyPrefix + ":" + 参数值
     */
    @BingCache(cacheName = "prefix", keyPrefix = "custom", expireTime = 120)
    public String withKeyPrefix(String code) {
        return "Prefix-Cache: " + code;
    }

    // ========== 缓存null值 ==========

    /**
     * 3. 缓存null值 (用于防穿透)
     * cacheNullValue = true 时，方法返回null也会被缓存
     */
    @BingCache(cacheName = "nullable", expireTime = 60, cacheNullValue = true)
    public String cacheNullValue(Long id) {
        cacheNullValueCallCount.incrementAndGet();
        if (id < 0) return null; // id为负时返回null，但仍会缓存
        return "Value: " + id;
    }

    /**
     * 4. 不缓存null值 (默认行为)
     * cacheNullValue = false (默认) 时，null结果不会被缓存
     */
    @BingCache(cacheName = "non-nullable", expireTime = 60, cacheNullValue = false)
    public String noCacheNullValue(Long id) {
        noCacheNullValueCallCount.incrementAndGet();
        return id > 0 ? "Value: " + id : null;
    }

    // ========== 不同过期时间场景 ==========

    /**
     * 5. 短过期时间 - 频繁变化的数据
     * 例如: 库存、实时价格
     */
    @BingCache(cacheName = "realtime", expireTime = 5)
    public String realtimeData(String symbol) {
        return "Price[" + symbol + "]: " + System.currentTimeMillis();
    }

    /**
     * 6. 长过期时间 - 配置类数据
     * 例如: 字典表、系统配置
     */
    @BingCache(cacheName = "config", expireTime = 3600) // 1小时
    public String configData(String configKey) {
        return "Config[" + configKey + "]: loaded";
    }

    // ========== 多参数方法 ==========

    /**
     * 7. 多参数方法
     * BingCache会将所有参数组合成缓存key
     */
    @BingCache(cacheName = "multi-param", expireTime = 120)
    public String multiParamQuery(String category, String keyword, Integer page) {
        return String.format("Query[%s,%s,page=%d]", category, keyword, page);
    }

    // ========== 对象作为参数 ==========

    /**
     * 8. 对象作为参数
     * 缓存 key 基于对象内容序列化生成
     */
    @BingCache(cacheName = "object-param", expireTime = 60)
    public String objectAsParam(UserQuery query) {
        return "UserQuery result: " + query;
    }

    /**
     * 8.1 使用 SpEL 从对象参数中提取字段生成 key
     */
    @BingCache(cacheName = "object-spel", argSpel = "#query.name", expireTime = 60)
    public String objectAsParamWithSpel(UserQuery query) {
        return "UserQuery SpEL result: " + query + " [time:" + System.currentTimeMillis() + "]";
    }

    /**
     * 8.2 多值 SpEL key：{#category, #page} 选取两个字段组合成多值 key.
     * keyword 不参与 key，相同 category+page、不同 keyword 应命中同一缓存。
     */
    @BingCache(cacheName = "multi-spel", argSpel = "{#category, #page}", expireTime = 60)
    public String multiValueSpelKey(String category, String keyword, Integer page) {
        return "MultiSpel[" + category + "," + keyword + ",page=" + page + "]: " + System.nanoTime();
    }

    // ========== 集合返回值 ==========

    /**
     * 9. 集合类返回值
     */
    @BingCache(cacheName = "list-data", expireTime = 180)
    public java.util.List<String> listData(String category) {
        listDataCallCount.incrementAndGet();
        java.util.List<String> list = new java.util.ArrayList<>();
        list.add(category + "-item1");
        list.add(category + "-item2");
        list.add(category + "-item3");
        return list;
    }

    // ========== 复杂对象缓存 ==========

    /**
     * 10. 复杂业务对象缓存
     */
    @BingCache(cacheName = "business", expireTime = 300)
    public BusinessData getBusinessData(String businessId) {
        BusinessData data = new BusinessData();
        data.setId(businessId);
        data.setName("Business-" + businessId);
        data.setTimestamp(System.currentTimeMillis());
        return data;
    }

    // ========== 用于演示的内部类 ==========

    public static class UserQuery {
        private String name;
        private Integer age;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Integer getAge() { return age; }
        public void setAge(Integer age) { this.age = age; }
        @Override
        public String toString() { return "UserQuery{name=" + name + ", age=" + age + "}"; }
    }

    public static class BusinessData {
        private String id;
        private String name;
        private long timestamp;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        @Override
        public String toString() {
            return "BusinessData{id=" + id + ", name=" + name + ", timestamp=" + timestamp + "}";
        }
    }
}
