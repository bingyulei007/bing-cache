#!/usr/bin/env bash
# ============================================================
# Multi-Instance Startup Script for bing-cache-demo
#
# Usage:
#   ./scripts/start-instances.sh [count] [start-port]
#
#   count      - Number of instances (default: 3)
#   start-port - Starting port number (default: 8081)
#
# Examples:
#   ./scripts/start-instances.sh            # Start 3 instances on 8081-8083
#   ./scripts/start-instances.sh 2 8090     # Start 2 instances on 8090-8091
# ============================================================

set -euo pipefail

# === Configuration ===
COUNT="${1:-3}"
START_PORT="${2:-8081}"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PID_FILE="$PROJECT_DIR/.instance-pids.txt"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Cleanup on exit
cleanup() {
    echo ""
    echo -e "${YELLOW}=== Stopping all instances...${NC}"
    if [ -f "$PID_FILE" ]; then
        while IFS='=' read -r profile pid; do
            if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
                kill "$pid" 2>/dev/null || true
                echo -e "  Stopped instance ${CYAN}$profile${NC} (PID: $pid)"
            fi
        done < "$PID_FILE"
        rm -f "$PID_FILE"
    fi
    echo -e "${GREEN}All instances stopped.${NC}"
}
trap cleanup EXIT INT TERM

# === Check prerequisites ===
echo -e "${CYAN}=== bing-cache Multi-Instance Launcher ===${NC}"
echo "Project: $PROJECT_DIR"
echo "Instances: $COUNT (ports $START_PORT-$((START_PORT + COUNT - 1)))"
echo ""

# Validate that a jar is an executable Spring Boot fat jar (has Start-Class in manifest)
is_executable_jar() {
    local jar="$1"
    [ -n "$jar" ] && [ -f "$jar" ] && \
        unzip -p "$jar" META-INF/MANIFEST.MF 2>/dev/null | grep -q "Start-Class"
}

# Find an existing fat jar first (skip build if already available)
JAR_FILE=$(ls -t "$PROJECT_DIR/target/bing-cache-test-"*.jar 2>/dev/null | head -1)
if ! is_executable_jar "$JAR_FILE"; then
    # Build the project
    echo -e "${YELLOW}=== Building project...${NC}"
    cd "$PROJECT_DIR"
    mvn -q package -DskipTests || {
        echo -e "${RED}ERROR: Build failed${NC}"
        exit 1
    }
    JAR_FILE=$(ls -t "$PROJECT_DIR/target/bing-cache-test-"*.jar 2>/dev/null | head -1)
    if ! is_executable_jar "$JAR_FILE"; then
        echo -e "${RED}ERROR: Build did not produce an executable fat jar${NC}"
        exit 1
    fi
    echo -e "${GREEN}Build completed.${NC}"
fi

# Convert to Windows path for java.exe compatibility (Git Bash /c/... -> C:\...)
if command -v cygpath >/dev/null 2>&1; then
    JAR_WIN=$(cygpath -w "$JAR_FILE")
else
    JAR_WIN=$(echo "$JAR_FILE" | sed 's|^/\([a-zA-Z]\)/|\1:/|; s|/|\\|g')
fi

echo -e "${GREEN}Using jar: $(basename "$JAR_FILE")${NC}"
echo ""

# === Start instances ===
echo -e "${YELLOW}=== Starting instances...${NC}"
> "$PID_FILE"  # Clear pid file

for i in $(seq 0 $((COUNT - 1))); do
    port=$((START_PORT + i))
    profile="instance$((i + 1))"

    echo -n "  Starting instance $((i + 1)) (profile=$profile, port=$port)... "

    # Run in background, redirect output to log file
    log_file="$PROJECT_DIR/target/instance-$profile.log"
    java -jar "$JAR_WIN" --spring.profiles.active="$profile" \
        > "$log_file" 2>&1 &

    pid=$!
    echo "$profile=$pid" >> "$PID_FILE"
    echo -e "${GREEN}OK${NC} (PID: $pid, log: $log_file)"
done

# === Wait for readiness ===
echo ""
echo -e "${YELLOW}=== Waiting for instances to become ready...${NC}"

MAX_WAIT=60
WAIT_INTERVAL=2
elapsed=0

while [ $elapsed -lt $MAX_WAIT ]; do
    all_ready=true

    for i in $(seq 0 $((COUNT - 1))); do
        port=$((START_PORT + i))
        profile="instance$((i + 1))"

        if curl -s "http://localhost:$port/api/instance-info" > /dev/null 2>&1; then
            if [ "$elapsed" -eq 0 ]; then
                echo -e "  Instance ${GREEN}$profile${NC} (port $port): ${GREEN}READY${NC}"
            fi
        else
            all_ready=false
        fi
    done

    if [ "$all_ready" = true ]; then
        break
    fi

    elapsed=$((elapsed + WAIT_INTERVAL))
    if [ $elapsed -lt $MAX_WAIT ]; then
        sleep "$WAIT_INTERVAL"
    fi
done

if [ "$all_ready" = false ]; then
    echo -e "${RED}ERROR: Not all instances became ready within ${MAX_WAIT}s${NC}"
    echo "Check log files in target/instance-*.log for details."
    exit 1
fi

echo ""
echo -e "${GREEN}============================================${NC}"
echo -e "${GREEN}  All $COUNT instances are READY!${NC}"
echo -e "${GREEN}============================================${NC}"
echo ""
echo "Instance endpoints:"
for i in $(seq 0 $((COUNT - 1))); do
    port=$((START_PORT + i))
    echo -e "  ${CYAN}Instance $((i + 1))${NC}: http://localhost:$port/api/instance-info"
done

echo ""
echo -e "${YELLOW}Press Ctrl+C to stop all instances.${NC}"
echo ""

# Keep script running until interrupted
while true; do
    sleep 5

    # Health check - verify all instances are still alive
    for i in $(seq 0 $((COUNT - 1))); do
        port=$((START_PORT + i))
        if ! curl -s "http://localhost:$port/api/instance-info" > /dev/null 2>&1; then
            echo -e "${RED}WARNING: Instance on port $port is not responding!${NC}"
        fi
    done
done
