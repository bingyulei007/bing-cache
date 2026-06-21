# Multi-Instance Cache Test

## 概述

本目录包含 bing-cache 多实例部署测试工具，用于模拟多个服务实例共享 Redis 的场景，
验证分布式缓存的一致性和可靠性。

## 架构说明

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Instance 1 │     │  Instance 2 │     │  Instance 3 │
│  Port 8081  │     │  Port 8082  │     │  Port 8083  │
│  ┌───────┐  │     │  ┌───────┐  │     │  ┌───────┐  │
│  │Caffeine│  │     │  │Caffeine│  │     │  │Caffeine│  │
│  │  L1    │  │     │  │  L1    │  │     │  │  L1    │  │
│  └───┬───┘  │     │  └───┬───┘  │     │  └───┬───┘  │
│      │      │     │      │      │     │      │      │
│      └──────┼─────┼──────┼──────┼─────┼──────┘      │
│             │     │      │      │     │              │
│         ┌───┴─────┴──────┴──────┴─────┴───┐          │
│         │         Redis (L2)              │          │
│         │     cmac-mini:6379              │          │
│         └────────────────────────────────┘          │
│                                                     │
│   Cache Invalidation: Redis Pub/Sub                 │
│   L1/L2 Sync: Reconciliation every 30s              │
└─────────────────────────────────────────────────────┘
```

## 前提条件

1. **Redis 服务** `cmac-mini:6379` 已启动，密码 `RedRain123`
2. **Java 17** 和 **Maven** 已安装（更高版本 JDK 需按 release 17 编译）
3. 父 reactor 已正确配置 `cn.com.bingbing:bing-cache:${project.version}` 依赖，运行命令时使用 `-pl bing-cache-test -am` 构建 core 模块

## 快速开始

### 1. 启动多实例

```bash
# 启动 3 个实例（端口 8081, 8082, 8083）
./scripts/start-instances.sh

# 或自定义数量
./scripts/start-instances.sh 2 9090    # 2 个实例，从 9090 开始
```

脚本会自动：
- 编译打包项目
- 依次启动各实例（每个使用独立端口）
- 等待所有实例健康检查通过
- 按 `Ctrl+C` 优雅停止所有实例

### 2. 运行多实例测试

```bash
# 先确保多实例已启动
./scripts/start-instances.sh 3

# 在另一个终端从父仓库根目录运行测试（去掉 @Disabled 注解）
mvn -pl bing-cache-test -am -Dtest=MultiInstanceCacheTest test
```

> 注意：`MultiInstanceCacheTest` 默认被 `@Disabled` 禁用，运行前需去掉或注释掉该注解。

### 3. 手动验证

```bash
# 实例1: 查询缓存数据
curl http://localhost:8081/api/user/5001

# 实例2: 验证跨实例缓存命中（costMs 应很短）
curl http://localhost:8082/api/user/5001

# 实例1: 触发缓存失效
curl -X POST "http://localhost:8081/demo/user/update/5001?name=test"

# 实例2: 验证跨实例缓存已失效（costMs 应较长）
curl http://localhost:8082/api/user/5001
```

## 测试场景

| # | 测试方法 | 验证要点 |
|---|----------|----------|
| 1 | `testL2CacheSharedAcrossInstances` | 实例1写缓存 → 实例2读缓存命中 |
| 2 | `testEvictPropagationAcrossInstances` | 实例1 evict → 实例2重新执行方法 |
| 3 | `testConcurrentAccessMultipleInstances` | 3实例并发读写，无错误 |
| 4 | `testStressMultipleInstances` | 大量混合请求，各实例稳定 |

## 配置文件

| 文件 | 端口 | 说明 |
|------|------|------|
| `application-instance1.yml` | 8081 | 实例1 |
| `application-instance2.yml` | 8082 | 实例2 |
| `application-instance3.yml` | 8083 | 实例3 |

所有实例共享 `application.yml` 中的 Redis 和 bing-cache 配置。

## 目录结构

```
bing-cache-test/
├── scripts/
│   └── start-instances.sh          # 多实例启动脚本
├── src/
│   ├── main/
│   │   ├── java/com/example/demo/
│   │   │   └── DemoController.java  # 含 /api/instance-info 端点
│   │   └── resources/
│   │       ├── application.yml                    # 基础配置
│   │       ├── application-instance1.yml          # 实例1配置
│   │       ├── application-instance2.yml          # 实例2配置
│   │       └── application-instance3.yml          # 实例3配置
│   └── test/
│       └── java/com/example/demo/
│           ├── BingCacheTest.java                 # 单实例功能测试
│           └── MultiInstanceCacheTest.java        # 多实例集成测试
└── MULTI_INSTANCE_TEST.md                         # 本文档
```
