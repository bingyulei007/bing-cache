package com.example.demo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * 演示 REST API
 */
@RestController
@RequestMapping("/api")
public class DemoController {

    @Autowired
    private DemoService demoService;

    @Value("${server.port}")
    private int serverPort;

    /**
     * 实例信息端点 - 用于多实例测试中区分不同实例
     */
    @GetMapping("/instance-info")
    public Map<String, Object> instanceInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("port", serverPort);
        try {
            info.put("hostname", InetAddress.getLocalHost().getHostName());
        } catch (Exception e) {
            info.put("hostname", "unknown");
        }
        info.put("pid", ProcessHandle.current().pid());
        info.put("status", "UP");
        return info;
    }

    @GetMapping("/user/{id}")
    public Map<String, Object> getUser(@PathVariable Long id) {
        long start = System.currentTimeMillis();
        String result = demoService.getUserById(id);
        long cost = System.currentTimeMillis() - start;

        Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("data", result);
        map.put("costMs", cost);
        return map;
    }

    @GetMapping("/product/{code}")
    public Map<String, Object> getProduct(@PathVariable String code) {
        long start = System.currentTimeMillis();
        String result = demoService.getProductByCode(code);
        long cost = System.currentTimeMillis() - start;

        Map<String, Object> map = new HashMap<>();
        map.put("code", code);
        map.put("data", result);
        map.put("costMs", cost);
        return map;
    }

    @GetMapping("/order/{id}")
    public Map<String, Object> getOrder(@PathVariable Long id) {
        long start = System.currentTimeMillis();
        String result = demoService.getOrderById(id);
        long cost = System.currentTimeMillis() - start;

        Map<String, Object> map = new HashMap<>();
        map.put("orderId", id);
        map.put("data", result);
        map.put("costMs", cost);
        return map;
    }

    @GetMapping("/nocache/{key}")
    public Map<String, Object> getNoCache(@PathVariable String key) {
        long start = System.currentTimeMillis();
        String result = demoService.getNoCacheData(key);
        long cost = System.currentTimeMillis() - start;

        Map<String, Object> map = new HashMap<>();
        map.put("key", key);
        map.put("data", result);
        map.put("costMs", cost);
        return map;
    }
}
