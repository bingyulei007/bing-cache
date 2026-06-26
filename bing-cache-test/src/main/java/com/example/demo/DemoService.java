package com.example.demo;

import com.bing.cache.annotation.BingCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 演示 BingCache 用法的服务类
 */
@Service
public class DemoService {

    private static final Logger LOG = LoggerFactory.getLogger(DemoService.class);
    private final Random random = new Random();

    /**
     * 各缓存方法的方法体执行计数，用于测试区分"命中缓存"与"重新执行方法"。
     * 命中缓存不会进入方法体，计数不增长；缓存未命中或被驱逐后重算时计数递增。
     */
    private final AtomicLong getOrderByIdCallCount = new AtomicLong();

    public long getGetOrderByIdCallCount() {
        return getOrderByIdCallCount.get();
    }

    /**
     * 带缓存的方法 - 缓存 60 秒
     * 第一次调用会执行方法体，后续调用直接返回缓存结果
     */
    @BingCache(cacheName = "user", expireTime = 60)
    public String getUserById(Long id) {
        LOG.info("[BingCache] 执行 getUserById({})，模拟数据库查询...", id);
        try {
            Thread.sleep(500); // 模拟耗时操作
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "User-" + id + " [" + LocalDateTime.now() + "]";
    }

    /**
     * 带缓存的方法 - 使用 keyPrefix
     */
    @BingCache( keyPrefix = "product", expireTime = 120)
    public String getProductByCode(String code) {
        LOG.info("[BingCache] 执行 getProductByCode({})，模拟数据库查询...", code);
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "Product-" + code + " [" + LocalDateTime.now() + "]";
    }

    /**
     * 缓存 null 值的方法
     */
    @BingCache(cacheName = "order", expireTime = 60, cacheNullValue = true)
    public String getOrderById(Long orderId) {
        getOrderByIdCallCount.incrementAndGet();
        LOG.info("[BingCache] 执行 getOrderById({})...", orderId);
        // 模拟返回 null
        if (orderId > 1000) {
            System.out.println("##进入数据库查询");
            return null;
        }
        return "Order-" + orderId + " [" + LocalDateTime.now() + "]";
    }

    /**
     * 无缓存的方法 - 用于对比
     */
    public String getNoCacheData(String key) {
        LOG.info("[NO CACHE] 执行 getNoCacheData({})...", key);
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "NoCache-" + key + " [" + LocalDateTime.now() + "]";
    }
}
