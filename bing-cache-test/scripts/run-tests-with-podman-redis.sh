#!/usr/bin/env bash
# ============================================================
# Run bing-cache-test against a temporary Podman Redis
#
# 当 application.yml 中配置的 Redis (cmac-mini:6379) 不可用时，
# 用本地 Podman 起一个临时 Redis 顶替，跑完测试自动清理。
#
# 原理：Podman on Windows (WSL) 的容器端口不会暴露给 Windows localhost，
# 但绑定在 WSL VM 的 0.0.0.0 上，因此拿到 VM IP 后宿主机即可直连。
#
# 配置需与 application.yml 对齐：
#   spring.data.redis.port     = 6379
#   spring.data.redis.password = RedRain123
#   spring.data.redis.database = 0
#
# Usage:
#   ./scripts/run-tests-with-podman-redis.sh                   # 跑全部启用的测试类
#   ./scripts/run-tests-with-podman-redis.sh -Dtest=BingCacheTest   # 只跑指定测试类
#   ./scripts/run-tests-with-podman-redis.sh -Dtest=BingCacheTest#testBasicCache
#
# 前置：Podman machine 已启动 (podman machine start)
# ============================================================

set -euo pipefail

# === Configuration (与 application.yml 对齐) ===
CONTAINER_NAME="bing-cache-test-redis"
REDIS_IMAGE="docker.io/library/redis:7-alpine"
REDIS_PORT=6379
REDIS_PASSWORD="RedRain123"        # application.yml: spring.data.redis.password
REDIS_HOST_PROPERTY="spring.data.redis.host"

# 默认跑全部启用的测试类 (MultiInstanceCacheTest 是 @Disabled，自然跳过)
DEFAULT_TEST_CLASSES="BingCacheTest,AdvancedBingCacheTest,GroupAndAdvancedTest"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m'

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

# === Cleanup: 停掉并删除临时容器 ===
cleanup() {
    echo ""
    echo -e "${YELLOW}=== Cleaning up Podman Redis container...${NC}"
    podman stop "$CONTAINER_NAME" >/dev/null 2>&1 || true
    podman rm "$CONTAINER_NAME" >/dev/null 2>&1 || true
    echo -e "${GREEN}Cleanup done.${NC}"
}
trap cleanup EXIT INT TERM

# === Prerequisites ===
echo -e "${CYAN}=== bing-cache-test with Podman Redis ===${NC}"
echo "Repo: $REPO_ROOT"

if ! command -v podman >/dev/null 2>&1; then
    echo -e "${RED}ERROR: podman not found in PATH${NC}"
    exit 1
fi

if ! podman info >/dev/null 2>&1; then
    echo -e "${RED}ERROR: cannot connect to Podman. Run 'podman machine start' first.${NC}"
    exit 1
fi

# 清理可能残留的同名容器
podman stop "$CONTAINER_NAME" >/dev/null 2>&1 || true
podman rm "$CONTAINER_NAME" >/dev/null 2>&1 || true

# === Start Redis ===
echo -e "${YELLOW}=== Starting Redis container ($REDIS_IMAGE)...${NC}"
podman run -d --name "$CONTAINER_NAME" -p "${REDIS_PORT}:6379" "$REDIS_IMAGE" \
    redis-server --requirepass "$REDIS_PASSWORD"

# === Wait for Redis readiness ===
echo -e "${YELLOW}=== Waiting for Redis to be ready...${NC}"
MAX_WAIT=30
elapsed=0
while [ $elapsed -lt $MAX_WAIT ]; do
    if podman exec "$CONTAINER_NAME" redis-cli -a "$REDIS_PASSWORD" --no-auth-warning PING 2>/dev/null | grep -q PONG; then
        echo -e "  Redis: ${GREEN}READY${NC}"
        break
    fi
    elapsed=$((elapsed + 1))
    sleep 1
done
if [ $elapsed -ge $MAX_WAIT ]; then
    echo -e "${RED}ERROR: Redis did not become ready within ${MAX_WAIT}s${NC}"
    podman logs "$CONTAINER_NAME" || true
    exit 1
fi

# === Resolve WSL VM IP (Podman 端口不对 Windows localhost 暴露，需用 VM IP) ===
echo -e "${YELLOW}=== Resolving Podman WSL VM IP...${NC}"
VM_IP=$(wsl -d podman-machine-default sh -c 'ip -4 addr show eth0 2>/dev/null | grep -oP "inet \K[0-9.]+" | head -1' 2>/dev/null || true)
if [ -z "$VM_IP" ]; then
    echo -e "${RED}ERROR: could not resolve Podman WSL VM IP${NC}"
    exit 1
fi
echo -e "  VM IP: ${CYAN}$VM_IP${NC}"

# === Build mvn args ===
# 检查用户是否传了 -Dtest；没有则用默认全部启用测试类
HAS_TEST=false
for arg in "$@"; do
    case "$arg" in
        -Dtest=*) HAS_TEST=true ;;
    esac
done

MVN_ARGS=("-Dsurefire.failIfNoSpecifiedTests=false")
if [ "$HAS_TEST" = false ]; then
    MVN_ARGS+=("-Dtest=${DEFAULT_TEST_CLASSES}")
fi
MVN_ARGS+=("$@")

# === Run tests ===
echo ""
echo -e "${YELLOW}=== Running tests with -D${REDIS_HOST_PROPERTY}=$VM_IP ===${NC}"
cd "$REPO_ROOT"
mvn -pl bing-cache-test -am "${MVN_ARGS[@]}" \
    "-D${REDIS_HOST_PROPERTY}=${VM_IP}" test
