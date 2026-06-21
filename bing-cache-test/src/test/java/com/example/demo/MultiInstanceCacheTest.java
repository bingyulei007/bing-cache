package com.example.demo;

import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多实例部署缓存一致性测试
 *
 * 测试多模块部署场景下 bing-cache 的分布式缓存行为：
 * - L2 Redis 缓存共享
 * - 跨实例缓存失效传播（Redis pub/sub）
 * - 并发访问稳定性
 * - 压力测试
 *
 * 前提条件：
 *   - Redis 服务已启动 (cmac-mini:6379)
 *   - 至少 2 个实例已通过脚本启动:
 *     ./scripts/start-instances.sh 3
 *
 * 手动启用：在 @Disabled 注解上去掉或注释掉
 */
@Disabled("需要手动启动多实例后运行: ./scripts/start-instances.sh 3")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MultiInstanceCacheTest {

    private static final String INSTANCE1_URL = "http://localhost:8081";
    private static final String INSTANCE2_URL = "http://localhost:8082";
    private static final String INSTANCE3_URL = "http://localhost:8084";

    private RestClient client1;
    private RestClient client2;
    private RestClient client3;

    @BeforeEach
    void setUp() {
        client1 = RestClient.create(INSTANCE1_URL);
        client2 = RestClient.create(INSTANCE2_URL);
        client3 = RestClient.create(INSTANCE3_URL);
    }

    /**
     * 验证所有实例都已启动
     */
    @Test
    @Order(1)
    @DisplayName("Sanity check - 所有实例可访问")
    void testAllInstancesUp() {
        ResponseEntity<Map> r1 = client1.get().uri("/api/instance-info").retrieve().toEntity(Map.class);
        ResponseEntity<Map> r2 = client2.get().uri("/api/instance-info").retrieve().toEntity(Map.class);
        ResponseEntity<Map> r3 = client3.get().uri("/api/instance-info").retrieve().toEntity(Map.class);

        assertEquals(HttpStatusCode.valueOf(200), r1.getStatusCode());
        assertEquals(HttpStatusCode.valueOf(200), r2.getStatusCode());
        assertEquals(HttpStatusCode.valueOf(200), r3.getStatusCode());

        assertNotNull(r1.getBody());
        assertNotNull(r2.getBody());
        assertNotNull(r3.getBody());

        System.out.println("Instance 1 info: " + r1.getBody());
        System.out.println("Instance 2 info: " + r2.getBody());
        System.out.println("Instance 3 info: " + r3.getBody());
    }

    // ========== 场景1: 跨实例 L2 缓存共享 ==========

    /**
     * 验证: 实例1 缓存数据后，实例2 读取同一 key 应命中缓存（L2 Redis 共享）
     *
     * 流程:
     *   1. 实例1 GET /api/user/5001 → 首次执行，costMs ≈ 500ms
     *   2. 实例2 GET /api/user/5001 → 应缓存命中，costMs < 10ms
     */
    @Test
    @Order(2)
    @DisplayName("跨实例 L2 缓存共享")
    void testL2CacheSharedAcrossInstances() {
        Long userId = 5001L;
        String uri = "/api/user/" + userId;

        // Step 1: 实例1 首次查询，写入 L1 + L2
        Map<String, Object> r1 = client1.get().uri(uri).retrieve().body(Map.class);
        assertNotNull(r1);
        double cost1 = Double.parseDouble(r1.get("costMs").toString());
        System.out.printf("[Instance1] 首次查询 userId=%d, costMs=%.3f%n", userId, cost1);

        // 短暂等待，确保 L2 写入完成
        try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // Step 2: 实例2 查询同一 userId，应从 L2 Redis 获取缓存
        Map<String, Object> r2 = client2.get().uri(uri).retrieve().body(Map.class);
        assertNotNull(r2);
        double cost2 = Double.parseDouble(r2.get("costMs").toString());
        System.out.printf("[Instance2] 查询 userId=%d, costMs=%.3f%n", userId, cost2);

        // 实例2 应该是缓存命中（很快）
        assertTrue(cost2 < cost1, "实例2 应通过 L2 缓存命中，耗时更短");
        System.out.println("PASS: 跨实例 L2 缓存共享验证通过");
    }

    // ========== 场景2: 跨实例缓存失效传播 ==========

    /**
     * 验证: 实例1 失效缓存后，实例2 再次查询应重新执行
     *
     * 流程:
     *   1. 实例1 GET /demo/user/6001 → 缓存用户数据
     *   2. 实例2 GET /demo/user/6001 → 验证缓存命中
     *   3. 实例1 POST /demo/user/update/6001?name=test → 失效缓存
     *   4. 实例2 GET /demo/user/6001 → 应重新执行（数据变化）
     */
    @Test
    @Order(3)
    @DisplayName("跨实例缓存失效 (evict) 传播")
    void testEvictPropagationAcrossInstances() throws Exception {
        Long userId = 6001L;
        String getUri = "/demo/user/" + userId;

        // Step 1: 实例1 查询并缓存
        Map<String, Object> r1 = client1.get().uri(getUri).retrieve().body(Map.class);
        String dataBefore = r1.get("data").toString();
        System.out.println("[Instance1] 查询结果: " + dataBefore);

        // 等缓存写入
        Thread.sleep(300);

        // Step 2: 实例2 读取，验证缓存命中（数据相同）
        Map<String, Object> r2 = client2.get().uri(getUri).retrieve().body(Map.class);
        String dataOnInstance2 = r2.get("data").toString();
        assertEquals(dataBefore, dataOnInstance2, "实例2 应读到相同的缓存数据");
        System.out.println("[Instance2] 缓存命中: " + dataOnInstance2);

        // Step 3: 实例1 更新用户，触发缓存失效
        Thread.sleep(5); // 确保时间戳可区分
        client1.post()
                .uri("/demo/user/update/" + userId + "?name=multi-instance-test")
                .retrieve()
                .toBodilessEntity();
        System.out.println("[Instance1] 已触发 evict");

        // 等待失效消息通过 Redis pub/sub 传播到实例2
        Thread.sleep(500);

        // Step 4: 实例2 再次查询，应重新执行
        Map<String, Object> r2After = client2.get().uri(getUri).retrieve().body(Map.class);
        String dataAfter = r2After.get("data").toString();
        System.out.println("[Instance2] evict后查询: " + dataAfter);

        assertNotEquals(dataOnInstance2, dataAfter,
                "实例1 evict 后，实例2 应重新执行方法（通过 Redis pub/sub 收到失效通知）");
        System.out.println("PASS: 跨实例缓存失效传播验证通过");
    }

    // ========== 场景3: 并发访问稳定性 ==========

    /**
     * 验证: 多实例并发访问同一 key 不会导致错误或脏数据
     *
     * 流程:
     *   - 3 个线程分别对应 3 个实例，并发查询同一 userId
     *   - 验证所有响应都成功返回
     */
    @Test
    @Order(4)
    @DisplayName("并发访问多实例稳定性")
    void testConcurrentAccessMultipleInstances() throws Exception {
        Long userId = 7001L;
        String uri = "/api/user/" + userId;
        int concurrentCalls = 30;

        ExecutorService executor = Executors.newFixedThreadPool(3);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();

        // 首先确保缓存已预热
        client1.get().uri(uri).retrieve().body(Map.class);
        Thread.sleep(200);

        // 并发请求
        RestClient[] clients = {client1, client2, client3};
        String[] instanceNames = {"Instance1", "Instance2", "Instance3"};

        for (int i = 0; i < concurrentCalls; i++) {
            final int instanceIdx = i % 3;
            futures.add(executor.submit(() -> {
                try {
                    Map<String, Object> result = clients[instanceIdx].get()
                            .uri(uri)
                            .retrieve()
                            .body(Map.class);
                    if (result != null && result.get("data") != null) {
                        successCount.incrementAndGet();
                    } else {
                        errorCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    System.err.println(instanceNames[instanceIdx] + " error: " + e.getMessage());
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        System.out.printf("并发测试结果: 成功=%d, 失败=%d (共%d次请求)%n",
                successCount.get(), errorCount.get(), concurrentCalls);
        assertEquals(concurrentCalls, successCount.get(),
                "所有并发请求都应成功返回");
        assertEquals(0, errorCount.get(), "不应有错误");
        System.out.println("PASS: 并发访问多实例验证通过");
    }

    // ========== 场景4: 压力测试 ==========

    /**
     * 验证: 短时间内大量请求时各实例保持稳定
     *
     * 流程:
     *   - 向 3 个实例发送大量请求（含缓存命中、未命中、evict 操作）
     *   - 验证所有实例正常响应，无崩溃
     */
    @Test
    @Order(5)
    @DisplayName("多实例压力测试")
    void testStressMultipleInstances() throws Exception {
        int rounds = 20;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(6);
        List<Future<?>> futures = new ArrayList<>();

        for (int r = 0; r < rounds; r++) {
            final long uid = 8000L + (r % 10); // 10 个不同用户循环

            // 混合操作: 查询、evict、再查询
            futures.add(executor.submit(() -> {
                try {
                    // 查询
                    client1.get().uri("/api/user/" + uid).retrieve().body(Map.class);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                }
            }));

            futures.add(executor.submit(() -> {
                try {
                    // 不同实例上查询相同数据
                    client2.get().uri("/api/user/" + uid).retrieve().body(Map.class);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                }
            }));

            if (r % 3 == 0) {
                futures.add(executor.submit(() -> {
                    try {
                        // 偶尔失效缓存
                        client3.post()
                                .uri("/demo/user/update/" + uid + "?name=stress-test")
                                .retrieve()
                                .toBodilessEntity();
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    }
                }));
            }
        }

        for (Future<?> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }
        executor.shutdown();

        int totalRequests = successCount.get() + errorCount.get();
        System.out.printf("压力测试结果: 成功=%d, 失败=%d (共%d次请求)%n",
                successCount.get(), errorCount.get(), totalRequests);
        assertEquals(0, errorCount.get(), "压力测试中不应有错误");
        assertTrue(successCount.get() > 0, "至少应有成功的请求");
        System.out.println("PASS: 多实例压力测试通过");
    }

    // ========== 辅助方法 ==========

    private void clearAllCaches() {
        try {
            client1.delete().uri("/demo/cache/all").retrieve().toBodilessEntity();
            client2.delete().uri("/demo/cache/all").retrieve().toBodilessEntity();
            client3.delete().uri("/demo/cache/all").retrieve().toBodilessEntity();
        } catch (Exception ignored) {
            // 清空失败不阻塞测试
        }
    }
}
