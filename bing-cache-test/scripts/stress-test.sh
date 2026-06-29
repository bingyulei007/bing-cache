#!/usr/bin/env bash
# ============================================================
# Stress Test Script for bing-cache-demo
#
# Uses the localhost/rakyll/hey Podman image to run HTTP stress
# tests against running bing-cache demo instances, validating
# cache behavior under load.
#
# Prerequisites:
#   - Podman machine running (podman machine start)
#   - localhost/rakyll/hey image available locally
#   - Project built (mvn package -DskipTests)
#
# Usage:
#   # Full auto: start Redis + 3 instances + run all scenarios
#   ./scripts/stress-test.sh
#
#   # Only stress test (Redis + instances already running)
#   ./scripts/stress-test.sh --no-redis --no-start-instances
#
#   # Custom concurrency and request count
#   ./scripts/stress-test.sh -c 500 -n 20000
#
#   # Run specific scenarios
#   ./scripts/stress-test.sh --scenarios 1,3
#
# Notes on Windows + Podman WSL networking:
#   - 127.0.0.1 from inside a Podman container refers to the
#     container/Podman VM, NOT the Windows host
#   - This script auto-discovers the Windows host IP that is
#     reachable from Podman containers by probing candidate IPs
#   - The Podman VM eth0 IP is used for Redis (Windows→VM), and
#     the discovered HOST_IP is used for hey (VM→Windows)
# ============================================================

set -euo pipefail

# ====================================================================
# Configuration
# ====================================================================
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
REPO_ROOT="$(cd "$PROJECT_DIR/.." && pwd)"
PID_FILE="$PROJECT_DIR/.stress-instance-pids.txt"

# Defaults
TOTAL_REQUESTS=10000
CONCURRENCY=200
INSTANCE_COUNT=3
START_PORT=8081
RUN_SCENARIOS="1,2,3"

# Auto-start flags (default: auto-start everything)
AUTO_REDIS=true
AUTO_INSTANCES=true

# Redis container config (matches application.yml)
REDIS_CONTAINER="bing-cache-stress-redis"
REDIS_IMAGE="docker.io/library/redis:7-alpine"
REDIS_PORT=6379
REDIS_PASSWORD="RedRain123"

# Thresholds
CACHE_HIT_QPS_MIN=2000
CACHE_HIT_P99_MAX_MS=100
CACHE_HIT_STATUS_EXPECTED=200

# Colors (match existing scripts)
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m'

# Track results
PASS_COUNT=0
FAIL_COUNT=0
declare -a FAILURE_DETAILS

# ====================================================================
# Cleanup
# ====================================================================
cleanup() {
    echo ""
    echo -e "${YELLOW}=== Cleaning up...${NC}"

    # Kill background hey processes
    if [ -f "$PROJECT_DIR/.stress-hey-pids.txt" ]; then
        while read -r pid; do
            if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
                kill "$pid" 2>/dev/null || true
            fi
        done < "$PROJECT_DIR/.stress-hey-pids.txt"
        rm -f "$PROJECT_DIR/.stress-hey-pids.txt"
    fi

    # Kill Spring Boot instances (if auto-started)
    if [ "$AUTO_INSTANCES" = true ] && [ -f "$PID_FILE" ]; then
        while IFS='=' read -r profile pid; do
            if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
                kill "$pid" 2>/dev/null || true
                echo -e "  Stopped instance ${CYAN}$profile${NC} (PID: $pid)"
            fi
        done < "$PID_FILE"
        rm -f "$PID_FILE"
    fi

    # Stop Redis container (if auto-started)
    if [ "$AUTO_REDIS" = true ]; then
        podman stop "$REDIS_CONTAINER" >/dev/null 2>&1 || true
        podman rm "$REDIS_CONTAINER" >/dev/null 2>&1 || true
        echo -e "  Stopped Redis container"
    fi

    # Cleanup temp files
    rm -f "$PROJECT_DIR/.stress-hey-output"*.txt

    echo -e "${GREEN}Cleanup done.${NC}"
}
# EXIT trap alone is sufficient: when bash receives SIGINT/SIGTERM without an
# explicit handler, it exits and the EXIT trap fires once. Defining INT/TERM
# traps too would cause cleanup to run twice (handler + EXIT).
trap cleanup EXIT

# ====================================================================
# Parse CLI Flags
# ====================================================================
parse_args() {
    while [ $# -gt 0 ]; do
        case "$1" in
            -n)
                TOTAL_REQUESTS="$2"
                shift 2
                ;;
            -c)
                CONCURRENCY="$2"
                shift 2
                ;;
            --instances)
                INSTANCE_COUNT="$2"
                shift 2
                ;;
            --start-port)
                START_PORT="$2"
                shift 2
                ;;
            --no-redis)
                AUTO_REDIS=false
                shift
                ;;
            --no-start-instances)
                AUTO_INSTANCES=false
                shift
                ;;
            --scenarios)
                RUN_SCENARIOS="$2"
                shift 2
                ;;
            -h|--help)
                echo "Usage: $0 [OPTIONS]"
                echo ""
                echo "Options:"
                echo "  -n TOTAL          Total requests per hey run (default: 10000)"
                echo "  -c CONC           Concurrency level (default: 200)"
                echo "  --instances N     Number of instances (default: 3)"
                echo "  --start-port PORT Starting port (default: 8081)"
                echo "  --no-redis        Skip Redis auto-start"
                echo "  --no-start-instances  Skip instance auto-start"
                echo "  --scenarios LIST  Comma-separated scenario numbers (default: 1,2,3)"
                echo "  -h, --help        Show this help"
                exit 0
                ;;
            *)
                echo -e "${RED}Unknown option: $1${NC}"
                echo "Use -h for help"
                exit 1
                ;;
        esac
    done
}

# ====================================================================
# IP Discovery: Find Windows host IP reachable from Podman containers
#
# On Windows + Podman WSL:
#   - 127.0.0.1 from inside container = container/Podman VM localhost
#   - Podman VM eth0 IP = VM's own IP, not the Windows host
#   - We need the Windows host's IP on the shared virtual network
#
# Approach: enumerate Windows non-loopback IPv4 addresses, probe each
# from the hey container until one returns HTTP 200 on the target port.
# ====================================================================
resolve_host_ip() {
    local target_port="$1"
    local max_wait="${2:-30}"

    echo -e "${YELLOW}=== Discovering Windows host IP reachable from Podman...${NC}" >&2

    # Step 1: Get Windows non-loopback IPv4 addresses
    echo "  Fetching Windows IPv4 addresses..." >&2
    local windows_ips
    windows_ips=$(powershell.exe -NoProfile -Command \
        'Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike "127.*" } | Select-Object -ExpandProperty IPAddress' \
        2>/dev/null || true)

    if [ -z "$windows_ips" ]; then
        echo -e "${RED}ERROR: No Windows IPv4 addresses found${NC}" >&2
        return 1
    fi

    echo "  Candidates:" >&2
    echo "$windows_ips" | while read -r ip; do echo "    - $ip" >&2; done

    # Also check the Podman VM eth0 IP for diagnostics (this is NOT the right one,
    # but showing it helps confirm the distinction)
    local vm_ip
    vm_ip=$(wsl -d podman-machine-default sh -c \
        'ip -4 addr show eth0 2>/dev/null | grep -oP "inet \K[0-9.]+" | head -1' \
        2>/dev/null || true)
    if [ -n "$vm_ip" ]; then
        echo -e "  ${YELLOW}Podman VM eth0 IP: $vm_ip (for diagnostics, NOT used for hey)${NC}" >&2
        # Remove VM IP from candidates to avoid wasted probe
        windows_ips=$(echo "$windows_ips" | grep -v "^${vm_ip}$" || true)
    fi

    # Step 2: Probe each candidate IP from the hey container
    echo "  Probing candidates on port $target_port..." >&2
    local elapsed=0
    local interval=2

    while [ $elapsed -lt $max_wait ]; do
        # Read candidates fresh each cycle in case networking settles
        local candidates
        candidates=$(powershell.exe -NoProfile -Command \
            'Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike "127.*" } | Select-Object -ExpandProperty IPAddress' \
            2>/dev/null || true)

        for ip in $candidates; do
            # Skip the Podman VM IP
            if [ "$ip" = "$vm_ip" ]; then
                continue
            fi

            local result
            # hey CSV column 7 is HTTP status code; verified against rakyll/hey
            # as of 2024. If hey changes its CSV format, this will silently
            # return empty status and the probe will retry until timeout.
            result=$(podman run --rm localhost/rakyll/hey \
                -n 1 -c 1 -o csv \
                "http://$ip:$target_port/api/instance-info" \
                2>/dev/null || true)

            local status
            status=$(echo "$result" | tail -1 | cut -d',' -f7 2>/dev/null || true)

            if [ "$status" = "200" ]; then
                echo -e "  ${GREEN}FOUND: $ip (HTTP 200 on port $target_port)${NC}" >&2
                echo "$ip"
                return 0
            fi
        done

        elapsed=$((elapsed + interval))
        if [ $elapsed -lt $max_wait ]; then
            echo "  No reachable IP yet (elapsed ${elapsed}s), retrying..." >&2
            sleep "$interval"
        fi
    done

    echo -e "${RED}ERROR: Could not find reachable Windows host IP within ${max_wait}s${NC}" >&2
    echo "  Tried candidates:" >&2
    echo "$windows_ips" | while read -r ip; do echo "    - $ip:$target_port" >&2; done
    echo "" >&2
    echo "  Troubleshooting:" >&2
    echo "    1. Ensure instances are running on port $target_port" >&2
    echo "    2. Check Windows Firewall allows inbound connections on port $target_port" >&2
    echo "    3. Try from PowerShell: curl http://localhost:$target_port/api/instance-info" >&2
    return 1
}

# ====================================================================
# Auto-start temporary Redis container via Podman
# ====================================================================
start_redis() {
    echo -e "${YELLOW}=== Starting Redis container...${NC}"

    # Remove leftover container
    podman stop "$REDIS_CONTAINER" >/dev/null 2>&1 || true
    podman rm "$REDIS_CONTAINER" >/dev/null 2>&1 || true

    podman run -d --name "$REDIS_CONTAINER" \
        -p "${REDIS_PORT}:6379" \
        "$REDIS_IMAGE" \
        redis-server --requirepass "$REDIS_PASSWORD"

    # Wait for Redis readiness
    echo "  Waiting for Redis..."
    local max_wait=30
    local elapsed=0
    while [ $elapsed -lt $max_wait ]; do
        if podman exec "$REDIS_CONTAINER" \
            redis-cli -a "$REDIS_PASSWORD" --no-auth-warning PING 2>/dev/null | grep -q PONG; then
            echo -e "  Redis: ${GREEN}READY${NC}"
            break
        fi
        elapsed=$((elapsed + 1))
        sleep 1
    done

    if [ $elapsed -ge $max_wait ]; then
        echo -e "${RED}ERROR: Redis did not become ready within ${max_wait}s${NC}"
        podman logs "$REDIS_CONTAINER" || true
        exit 1
    fi

    # Resolve Podman VM IP (for Spring Boot instances to connect to Redis)
    VM_IP=$(wsl -d podman-machine-default sh -c \
        'ip -4 addr show eth0 2>/dev/null | grep -oP "inet \K[0-9.]+" | head -1' \
        2>/dev/null || true)

    if [ -z "$VM_IP" ]; then
        echo -e "${RED}ERROR: Could not resolve Podman WSL VM IP${NC}"
        exit 1
    fi
    echo -e "  Redis accessible at: ${CYAN}$VM_IP:$REDIS_PORT${NC}"
}

# ====================================================================
# Auto-start Spring Boot instances
# ====================================================================
start_instances() {
    local redis_host="${1:-}"

    echo -e "${YELLOW}=== Starting $INSTANCE_COUNT Spring Boot instances...${NC}"
    echo "  Project: $PROJECT_DIR"

    # Find a valid executable fat jar (check manifest for Spring Boot Start-Class)
    local jar_file
    jar_file=""
    for candidate in $(ls -t "$PROJECT_DIR/target/bing-cache-test-"*.jar 2>/dev/null); do
        if unzip -p "$candidate" META-INF/MANIFEST.MF 2>/dev/null | grep -q "Start-Class"; then
            jar_file="$candidate"
            break
        fi
    done

    if [ -z "$jar_file" ]; then
        echo "  Building project..."
        cd "$PROJECT_DIR"
        mvn package -DskipTests -q || {
            echo -e "${RED}ERROR: Build failed${NC}"
            exit 1
        }
        for candidate in $(ls -t "$PROJECT_DIR/target/bing-cache-test-"*.jar 2>/dev/null); do
            if unzip -p "$candidate" META-INF/MANIFEST.MF 2>/dev/null | grep -q "Start-Class"; then
                jar_file="$candidate"
                break
            fi
        done
        if [ -z "$jar_file" ]; then
            echo -e "${RED}ERROR: Build did not produce an executable fat jar${NC}"
            exit 1
        fi
        echo -e "  Build: ${GREEN}OK${NC}"
    fi

    # Convert to Windows path for java.exe (Git Bash /c/... → C:\...)
    local jar_win
    if command -v cygpath >/dev/null 2>&1; then
        jar_win=$(cygpath -w "$jar_file")
    else
        # Manual conversion: /c/Users/... → C:\Users\...
        jar_win=$(echo "$jar_file" | sed 's|^/\([a-zA-Z]\)/|\1:/|; s|/|\\|g')
    fi

    echo "  Jar: $(basename "$jar_file")"

    # Clear PID file
    > "$PID_FILE"

    for i in $(seq 0 $((INSTANCE_COUNT - 1))); do
        local port=$((START_PORT + i))
        local profile="instance$((i + 1))"
        local log_file="$PROJECT_DIR/target/stress-instance-$profile.log"

        echo -n "  Starting instance $((i + 1)) (profile=$profile, port=$port)... "

        # JVM args go before -jar, program args go after -jar
        local jvm_opts=""
        if [ -n "$redis_host" ]; then
            jvm_opts="-Dspring.data.redis.host=$redis_host"
        fi

        # Run in background via java -jar (avoids Git Bash Maven wrapper issues)
        # Use Windows path format for java.exe compatibility
        nohup java $jvm_opts -jar "$jar_win" \
            --spring.profiles.active="$profile" \
            > "$log_file" 2>&1 &
        local pid=$!
        echo "$profile=$pid" >> "$PID_FILE"
        echo -e "${GREEN}OK${NC} (PID: $pid, log: $log_file)"
    done

    # Wait for readiness
    echo ""
    echo "  Waiting for instances to become ready..."
    local max_wait=90
    local interval=2
    local elapsed=0
    local all_ready=false

    while [ $elapsed -lt $max_wait ]; do
        all_ready=true
        for i in $(seq 0 $((INSTANCE_COUNT - 1))); do
            local port=$((START_PORT + i))
            if ! curl -s "http://localhost:$port/api/instance-info" > /dev/null 2>&1; then
                all_ready=false
            fi
        done
        if [ "$all_ready" = true ]; then
            break
        fi
        elapsed=$((elapsed + interval))
        sleep "$interval"
    done

    if [ "$all_ready" = false ]; then
        echo -e "${RED}ERROR: Not all instances became ready within ${max_wait}s${NC}"
        for i in $(seq 0 $((INSTANCE_COUNT - 1))); do
            echo "  Instance $((i + 1)) log tail:"
            tail -10 "$PROJECT_DIR/target/stress-instance-instance$((i + 1)).log" 2>/dev/null || true
        done
        exit 1
    fi

    echo -e "  All instances: ${GREEN}READY${NC}"
}

# ====================================================================
# Parse hey text output into metrics
# Uses simple grep/sed for portability (no PCRE lookahead)
# ====================================================================
parse_hey_output() {
    local output_file="$1"
    local field="$2"

    case "$field" in
        status_200)
            # "  [200]	20 responses"
            grep '\[200\]' "$output_file" | awk '{print $2}' 2>/dev/null || echo "0"
            ;;
        qps)
            # "  Requests/sec:	1221.7891"
            grep "Requests/sec:" "$output_file" | awk '{print $2}' 2>/dev/null || echo "0"
            ;;
        p99_sec)
            # "  99% in 0.0890 secs" (appears for n>=100)
            grep "99%" "$output_file" | awk '{print $3}' 2>/dev/null || echo "999"
            ;;
        p50_sec)
            # "  50% in 0.0036 secs"
            grep "50%" "$output_file" | awk '{print $3}' 2>/dev/null || echo "999"
            ;;
        avg_sec)
            # "  Average:	0.0045 secs"
            grep "Average:" "$output_file" | awk '{print $2}' 2>/dev/null || echo "999"
            ;;
        *)
            echo "0"
            ;;
    esac
}

run_hey() {
    local label="$1"
    local url="$2"
    local extra_args="${3:-}"
    local output_file="$PROJECT_DIR/.stress-hey-output-$label.txt"

    echo -e "  ${CYAN}$label${NC}" >&2
    echo "    URL: $url" >&2

    # shellcheck disable=SC2086
    podman run --rm localhost/rakyll/hey $extra_args -n "$TOTAL_REQUESTS" -c "$CONCURRENCY" "$url" \
        > "$output_file" 2>&1

    local status_200 qps p99_sec p50_sec avg_sec p99_ms p50_ms avg_ms

    status_200=$(parse_hey_output "$output_file" status_200)
    qps=$(parse_hey_output "$output_file" qps)
    p99_sec=$(parse_hey_output "$output_file" p99_sec)
    p50_sec=$(parse_hey_output "$output_file" p50_sec)
    avg_sec=$(parse_hey_output "$output_file" avg_sec)

    p99_ms=$(awk "BEGIN { printf \"%.1f\", $p99_sec * 1000 }" 2>/dev/null || echo "?")
    p50_ms=$(awk "BEGIN { printf \"%.1f\", $p50_sec * 1000 }" 2>/dev/null || echo "?")
    avg_ms=$(awk "BEGIN { printf \"%.1f\", $avg_sec * 1000 }" 2>/dev/null || echo "?")

    echo "    Requests/sec : $qps" >&2
    echo "    Avg latency  : ${avg_ms}ms" >&2
    echo "    P50 latency  : ${p50_ms}ms" >&2
    echo "    P99 latency  : ${p99_ms}ms" >&2
    echo "    Status 200   : $status_200/$TOTAL_REQUESTS" >&2

    # Return metrics as pipe-delimited string on stdout for caller capture
    echo "$status_200|$qps|$p99_ms|$p50_ms|$avg_ms"
}

# ====================================================================
# Scenario 1: Cache Hit Throughput (Single Instance)
# ====================================================================
scenario_cache_hit_throughput() {
    local host_ip="$1"

    echo ""
    echo -e "${CYAN}========================================${NC}"
    echo -e "${CYAN}  Scenario 1: Cache Hit Throughput${NC}"
    echo -e "${CYAN}========================================${NC}"
    echo "  Concurrency: $CONCURRENCY, Requests: $TOTAL_REQUESTS"

    # Pre-warm the cache
    echo "  Pre-warming cache..."
    curl -s "http://localhost:$START_PORT/demo/user/1" > /dev/null 2>&1
    sleep 1

    local result
    result=$(run_hey "s1-cache-hit" "http://$host_ip:$START_PORT/demo/user/1")

    IFS='|' read -r status_count qps p99_ms p50_ms avg_ms <<< "$result"

    # Check thresholds
    local passed=true
    local details=""

    if [ "$status_count" -ne "$TOTAL_REQUESTS" ]; then
        passed=false
        details="$details  Expected $TOTAL_REQUESTS x 200, got $status_count"
    fi

    # Use bc or awk for float comparison
    if awk "BEGIN {exit !($qps < $CACHE_HIT_QPS_MIN)}"; then
        passed=false
        details="$details  QPS $qps < minimum $CACHE_HIT_QPS_MIN"
    fi

    if awk "BEGIN {exit !($p99_ms > $CACHE_HIT_P99_MAX_MS)}"; then
        passed=false
        details="$details  P99 $p99_ms ms > max $CACHE_HIT_P99_MAX_MS ms"
    fi

    record_result "Scenario 1: Cache Hit Throughput" "$passed" "$details"
}

# ====================================================================
# Scenario 2: Multi-Instance Concurrent Reads
# ====================================================================
scenario_multi_instance_reads() {
    local host_ip="$1"

    echo ""
    echo -e "${CYAN}========================================${NC}"
    echo -e "${CYAN}  Scenario 2: Multi-Instance Concurrent Reads${NC}"
    echo -e "${CYAN}========================================${NC}"
    echo "  Instances: $INSTANCE_COUNT, Concurrency: $CONCURRENCY, Requests: $TOTAL_REQUESTS each"

    # Pre-warm on all instances
    echo "  Pre-warming caches on all instances..."
    for i in $(seq 0 $((INSTANCE_COUNT - 1))); do
        local port=$((START_PORT + i))
        curl -s "http://localhost:$port/demo/user/1" > /dev/null 2>&1
    done
    sleep 1

    # Run hey on all instances in parallel (text mode, not csv)
    > "$PROJECT_DIR/.stress-hey-pids.txt"
    local all_passed=true
    local details=""

    for i in $(seq 0 $((INSTANCE_COUNT - 1))); do
        local port=$((START_PORT + i))
        local label="s2-instance-$((i + 1))"
        local output="$PROJECT_DIR/.stress-hey-output-$label.txt"

        podman run --rm localhost/rakyll/hey \
            -n "$TOTAL_REQUESTS" -c "$CONCURRENCY" \
            "http://$host_ip:$port/demo/user/1" \
            > "$output" 2>&1 &
        echo "$!" >> "$PROJECT_DIR/.stress-hey-pids.txt"
    done

    # Wait for all background jobs
    echo "  Waiting for all instances to complete..."
    set +e
    wait
    set -e

    # Parse results
    for i in $(seq 0 $((INSTANCE_COUNT - 1))); do
        local port=$((START_PORT + i))
        local label="s2-instance-$((i + 1))"
        local output="$PROJECT_DIR/.stress-hey-output-$label.txt"

        if [ ! -f "$output" ]; then
            echo "  Instance $((i + 1)) (port $port): ${RED}NO OUTPUT${NC}"
            all_passed=false
            details="$details  Instance $((i + 1)): no output captured"
            continue
        fi

        local status_count qps p99_ms p50_ms

        status_count=$(parse_hey_output "$output" status_200)
        qps=$(parse_hey_output "$output" qps)
        p99_sec=$(parse_hey_output "$output" p99_sec)
        p50_sec=$(parse_hey_output "$output" p50_sec)

        p99_ms=$(awk "BEGIN { printf \"%.1f\", $p99_sec * 1000 }" 2>/dev/null || echo "?")
        p50_ms=$(awk "BEGIN { printf \"%.1f\", $p50_sec * 1000 }" 2>/dev/null || echo "?")

        echo "  Instance $((i + 1)) (port $port): QPS=$qps, P50=${p50_ms}ms, P99=${p99_ms}ms, 200=$status_count/$TOTAL_REQUESTS"

        if [ "$status_count" -lt "$TOTAL_REQUESTS" ]; then
            all_passed=false
            details="$details  Instance $((i + 1)): only $status_count/$TOTAL_REQUESTS HTTP 200"
        fi
    done

    record_result "Scenario 2: Multi-Instance Reads" "$all_passed" "$details"
}

# ====================================================================
# Scenario 3: Read Under Eviction Pressure
# ====================================================================
scenario_eviction_under_load() {
    local host_ip="$1"

    echo ""
    echo -e "${CYAN}========================================${NC}"
    echo -e "${CYAN}  Scenario 3: Read Under Eviction Pressure${NC}"
    echo -e "${CYAN}========================================${NC}"

    # Pre-warm cache
    echo "  Pre-warming cache..."
    curl -s "http://localhost:$START_PORT/demo/user/1" > /dev/null 2>&1
    sleep 1

    # Get current cached value (to compare after eviction)
    local before_value
    before_value=$(curl -s "http://localhost:$START_PORT/demo/user/1" 2>/dev/null | grep -oE '"data"[[:space:]]*:[[:space:]]*"[^"]+"' | sed 's/.*"\([^"]*\)"$/\1/' || echo "")
    echo "  Before eviction: $before_value"

    # Start sustained read load in background (30s)
    echo "  Starting sustained read load (30s, concurrency 50)..."
    > "$PROJECT_DIR/.stress-hey-pids.txt"

    podman run --rm localhost/rakyll/hey \
        -z 30s -c 50 \
        "http://$host_ip:$START_PORT/demo/user/1" \
        > "$PROJECT_DIR/.stress-hey-output-s3-reads.txt" 2>&1 &
    local hey_pid=$!
    echo "$hey_pid" >> "$PROJECT_DIR/.stress-hey-pids.txt"

    sleep 3  # Let the read load stabilize

    # Run eviction loop (15 rounds, ~1.5s interval)
    echo "  Running eviction loop (15 rounds)..."
    local evict_failures=0
    local verify_failures=0

    for round in $(seq 1 15); do
        # Evict
        local evict_resp
        evict_resp=$(curl -s -X POST "http://localhost:$START_PORT/demo/user/update/1?name=stress" \
            -w "\n%{http_code}" 2>/dev/null || echo "FAIL")
        local evict_http_code
        evict_http_code=$(echo "$evict_resp" | tail -1)

        if [ "$evict_http_code" != "200" ]; then
            evict_failures=$((evict_failures + 1))
            echo -e "    ${RED}Round $round: evict FAILED (HTTP $evict_http_code)${NC}"
        fi

        sleep 0.5

        # Verify cache was cleared: after eviction, next read should return a
        # different value (new callSeq). BingCacheDemos.getUserById returns
        # "User-{id} [time:{callSeq}]", and callSeq is monotonically increasing.
        local after_value
        after_value=$(curl -s "http://localhost:$START_PORT/demo/user/1" 2>/dev/null | grep -oE '"data"[[:space:]]*:[[:space:]]*"[^"]+"' | sed 's/.*"\([^"]*\)"$/\1/' || echo "")

        if [ "$after_value" = "$before_value" ]; then
            verify_failures=$((verify_failures + 1))
            echo -e "    ${RED}Round $round: verify FAILED (data unchanged: $after_value)${NC}"
            # Only fail once per run - if data never changes, all rounds will fail
            break
        else
            # Update before_value for next round comparison
            before_value="$after_value"
        fi

        sleep 1
    done

    # Wait for hey to finish
    wait "$hey_pid" 2>/dev/null || true

    # Parse hey results using shared parser
    local hey_output="$PROJECT_DIR/.stress-hey-output-s3-reads.txt"
    local status_count
    status_count=$(parse_hey_output "$hey_output" status_200)

    echo ""
    echo "  Results:"
    echo "    Evict failures : $evict_failures"
    echo "    Verify failures: $verify_failures"
    echo "    Read 200 count : $status_count"

    local passed=true
    local details=""

    if [ "$evict_failures" -gt 0 ]; then
        passed=false
        details="$details  $evict_failures evict failures"
    fi
    if [ "$verify_failures" -gt 0 ]; then
        passed=false
        details="$details  $verify_failures verify failures (cache not properly invalidated)"
    fi

    # Also check that hey didn't produce errors
    if [ "$status_count" = "0" ]; then
        passed=false
        details="$details  No successful reads during stress period"
    fi

    record_result "Scenario 3: Read Under Eviction" "$passed" "$details"
}

# ====================================================================
# Record a test result
# ====================================================================
record_result() {
    local name="$1"
    local passed="$2"
    local detail="$3"

    if [ "$passed" = true ]; then
        PASS_COUNT=$((PASS_COUNT + 1))
        echo -e "  Result: ${GREEN}PASS${NC}"
    else
        FAIL_COUNT=$((FAIL_COUNT + 1))
        FAILURE_DETAILS+=("$name: $detail")
        echo -e "  Result: ${RED}FAIL${NC}${detail:+ - $detail}"
    fi
}

# ====================================================================
# Main
# ====================================================================
main() {
    parse_args "$@"

    echo -e "${CYAN}============================================${NC}"
    echo -e "${CYAN}  bing-cache Stress Test${NC}"
    echo -e "${CYAN}============================================${NC}"
    echo "  Concurrency : $CONCURRENCY"
    echo "  Requests    : $TOTAL_REQUESTS (per hey run)"
    echo "  Instances   : $INSTANCE_COUNT"
    echo "  Start port  : $START_PORT"
    echo "  Scenarios   : $RUN_SCENARIOS"
    echo "  Auto Redis  : $AUTO_REDIS"
    echo "  Auto Start  : $AUTO_INSTANCES"
    echo ""

    # Check prerequisites
    if ! command -v podman >/dev/null 2>&1; then
        echo -e "${RED}ERROR: podman not found in PATH${NC}"
        exit 1
    fi

    if ! podman info >/dev/null 2>&1; then
        echo -e "${RED}ERROR: Cannot connect to Podman. Run 'podman machine start' first.${NC}"
        exit 1
    fi

    # Verify hey image exists
    if ! podman image exists localhost/rakyll/hey 2>/dev/null; then
        echo -e "${RED}ERROR: localhost/rakyll/hey image not found. Pull it first:${NC}"
        echo "  podman pull docker.io/rakyll/hey"
        echo "  podman tag docker.io/rakyll/hey localhost/rakyll/hey"
        exit 1
    fi

    # Step 1: Auto-start Redis if needed
    local redis_host=""
    if [ "$AUTO_REDIS" = true ]; then
        start_redis
        redis_host="$VM_IP"
    fi

    # Step 2: Auto-start instances if needed
    if [ "$AUTO_INSTANCES" = true ]; then
        start_instances "$redis_host"
    fi

    # Step 3: Discover the Windows host IP reachable from Podman
    HOST_IP=$(resolve_host_ip "$START_PORT")
    echo ""
    echo -e "  Using host IP: ${CYAN}$HOST_IP${NC}"
    echo ""

    # Step 4: Run selected scenarios
    # Use -w (word boundary) so "1" doesn't match "10", "11", etc.
    if echo "$RUN_SCENARIOS" | grep -qw "1"; then
        scenario_cache_hit_throughput "$HOST_IP"
    fi

    if echo "$RUN_SCENARIOS" | grep -qw "2"; then
        scenario_multi_instance_reads "$HOST_IP"
    fi

    if echo "$RUN_SCENARIOS" | grep -qw "3"; then
        scenario_eviction_under_load "$HOST_IP"
    fi

    # Step 5: Print summary
    echo ""
    echo -e "${CYAN}============================================${NC}"
    echo -e "${CYAN}  Summary${NC}"
    echo -e "${CYAN}============================================${NC}"

    local total=$((PASS_COUNT + FAIL_COUNT))
    echo "  Passed: $PASS_COUNT / $total"

    if [ "$FAIL_COUNT" -gt 0 ]; then
        echo ""
        echo -e "${RED}  Failures:${NC}"
        for detail in "${FAILURE_DETAILS[@]}"; do
            echo -e "    ${RED}- $detail${NC}"
        done
        echo ""
        echo -e "${RED}  STRESS TEST FAILED${NC}"
        exit 1
    else
        echo ""
        echo -e "${GREEN}  ALL STRESS TESTS PASSED${NC}"
    fi
}

main "$@"
