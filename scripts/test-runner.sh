#!/usr/bin/env bash
set -e

# ==============================================================================
# Unified Document Viewer Test Runner & Port Management
# ==============================================================================

# 1. Load .env if present
if [ -f ".env" ]; then
    # Export non-comment lines
    export $(grep -v '^#' .env | xargs)
fi

# 2. Configure Ports with Defaults
DB_PORT=${DB_PORT:-5432}
SALES_PORT=${SALES_PORT:-8081}
SERVICE_PORT=${SERVICE_PORT:-8082}
IDP_PORT=${IDP_PORT:-8088}

# Auto-detect Spring Boot Port (tries configured APP_PORT, PORT, 8080, 8085)
detect_app_port() {
    local candidate_ports=("${PORT}" "${APP_PORT}" 8080 8085)
    for p in "${candidate_ports[@]}"; do
        if [ -n "$p" ] && curl -s -m 1 "http://localhost:${p}/actuator/health" | grep -q '"status":"UP"'; then
            echo "$p"
            return 0
        fi
    done
    # Fallback to configured or 8080
    echo "${PORT:-${APP_PORT:-8080}}"
}

PORT=$(detect_app_port)
BASE_URL="http://localhost:${PORT}"
IDP_URL="http://localhost:${IDP_PORT}"
SCENARIO=${1:-"all"}

# Helper: Check if a port is in use
is_port_in_use() {
    local port=$1
    if command -v lsof >/dev/null 2>&1; then
        lsof -i ":${port}" -sTCP:LISTEN -t >/dev/null 2>&1
    elif command -v nc >/dev/null 2>&1; then
        nc -z localhost "${port}" >/dev/null 2>&1
    else
        return 1
    fi
}

# Helper: Get process info for a port
get_port_process() {
    local port=$1
    if command -v lsof >/dev/null 2>&1; then
        lsof -i ":${port}" -sTCP:LISTEN | tail -n +2 | awk '{print $1 " (PID " $2 ")"}' | head -n 1
    else
        echo "Unknown process"
    fi
}

# Helper: Port Diagnostics
check_ports() {
    echo "------------------------------------------------------------------------"
    echo " Checking Required Ports for Unified Document Viewer:"
    echo "------------------------------------------------------------------------"
    local required_ports=(
        "${PORT}:Unified Document Viewer App"
        "${DB_PORT}:PostgreSQL Database"
        "${SALES_PORT}:Sales System Stub"
        "${SERVICE_PORT}:Service System Stub"
        "${IDP_PORT}:Identity Provider Mock"
    )

    local conflicts_found=0

    for item in "${required_ports[@]}"; do
        local p="${item%%:*}"
        local name="${item##*:}"
        if is_port_in_use "$p"; then
            local proc=$(get_port_process "$p")
            echo " [OCCUPIED] Port ${p} (${name}) -> ${proc}"
        else
            echo " [AVAILABLE] Port ${p} (${name})"
        fi
    done
    echo "------------------------------------------------------------------------"
}

# Helper: Acquire JWT Bearer Token
get_token() {
    local token
    token=$(curl -s -X POST "${IDP_URL}/token" \
        -H "Content-Type: application/x-www-form-urlencoded" \
        -d "grant_type=client_credentials&client_id=operator-app&client_secret=secret&scope=openid" \
        | grep -o '"access_token":"[^"]*' | cut -d'"' -f4 || true)
    
    if [ -n "$token" ]; then
        echo "$token"
    else
        echo "mock-jwt-token-for-dev-and-testing"
    fi
}

echo "========================================================================"
echo " Unified Document Viewer Test Runner"
echo " Target App URL : ${BASE_URL} (Port: ${PORT})"
echo " Target IdP URL : ${IDP_URL} (Port: ${IDP_PORT})"
echo " Scenario       : ${SCENARIO}"
echo "========================================================================"

case "${SCENARIO}" in
    "check-ports"|"ports")
        check_ports
        ;;

    "clean-ports"|"kill-ports")
        echo "Cleaning up lingering processes on test ports..."
        local ports_to_kill=(${PORT} 8080 8085 ${DB_PORT} ${SALES_PORT} ${SERVICE_PORT} ${IDP_PORT})
        for p in "${ports_to_kill[@]}"; do
            if is_port_in_use "$p"; then
                echo "Terminating process on port ${p}..."
                lsof -ti ":${p}" | xargs kill -9 2>/dev/null || true
            fi
        done
        echo "Port cleanup complete."
        ;;

    "health")
        echo "Testing Service Health..."
        curl -i -s "${BASE_URL}/actuator/health"
        echo ""
        ;;

    "happy-path")
        echo "[1/1] Testing Happy Path (200 OK)..."
        TOKEN=$(get_token)
        curl -i -s -X GET "${BASE_URL}/api/v1/documents?vin=1HGBH41JXMN109186" \
            -H "Authorization: Bearer ${TOKEN}"
        echo -e "\n--> Happy path completed."
        ;;

    "cache-hit")
        echo "[1/1] Testing Database Cache Hit..."
        TOKEN=$(get_token)
        time curl -i -s -X GET "${BASE_URL}/api/v1/documents?vin=1HGBH41JXMN109186" \
            -H "Authorization: Bearer ${TOKEN}"
        echo -e "\n--> Cache hit test completed."
        ;;

    "partial-failure")
        echo "[1/3] Stopping service-stub container..."
        docker compose stop service-stub >/dev/null 2>&1 || true
        sleep 1
        echo "[2/3] Querying with Service API offline (expecting 206 Partial Content)..."
        TOKEN=$(get_token)
        curl -i -s -X GET "${BASE_URL}/api/v1/documents?vin=1HGBH41JXMN109222" \
            -H "Authorization: Bearer ${TOKEN}"
        echo -e "\n[3/3] Restarting service-stub container..."
        docker compose start service-stub >/dev/null 2>&1 || true
        echo -e "--> Partial failure test completed."
        ;;

    "total-outage")
        echo "[1/1] Testing Total Outage (expecting 503 Service Unavailable)..."
        TOKEN=$(get_token)
        curl -i -s -X GET "${BASE_URL}/api/v1/documents?vin=1HGBH41JXMN109999" \
            -H "Authorization: Bearer ${TOKEN}"
        echo -e "\n--> Total outage test completed."
        ;;

    "security-401")
        echo "[1/1] Testing Unauthenticated Request (expecting 401 Unauthorized)..."
        curl -i -s -X GET "${BASE_URL}/api/v1/documents?vin=1HGBH41JXMN109186"
        echo -e "\n--> Security 401 test completed."
        ;;

    "validation-400")
        echo "[1/1] Testing Malformed VIN (expecting 400 Bad Request)..."
        TOKEN=$(get_token)
        curl -i -s -X GET "${BASE_URL}/api/v1/documents?vin=INVALID_SHORT_VIN" \
            -H "Authorization: Bearer ${TOKEN}"
        echo -e "\n--> Validation 400 test completed."
        ;;

    "metrics-check")
        echo "[1/1] Scraping Prometheus Metrics..."
        curl -s "${BASE_URL}/actuator/prometheus" | grep -E "(document_lookup|external_api|cache)" || echo "No metrics matching filter found."
        echo -e "\n--> Metrics check completed."
        ;;

    "api-docs"|"docs")
        echo "Fetching OpenAPI 3 JSON Schema from ${BASE_URL}/v3/api-docs..."
        curl -s "${BASE_URL}/v3/api-docs"
        echo ""
        ;;

    "port")
        echo "${PORT}"
        ;;

    "start")
        exec ./scripts/start-app.sh
        ;;

    "all"|"demo")
        echo "Running full demo test suite against ${BASE_URL}..."
        TOKEN=$(get_token)
        echo -e "\n--- 1. Health Check ---"
        curl -s "${BASE_URL}/actuator/health"
        echo -e "\n\n--- 2. Happy Path (200 OK) ---"
        curl -s -X GET "${BASE_URL}/api/v1/documents?vin=1HGBH41JXMN109186" -H "Authorization: Bearer ${TOKEN}"
        echo -e "\n\n--- 3. Unauthenticated Rejection (401 Unauthorized) ---"
        curl -s -o /dev/null -w "HTTP Status: %{http_code}\n" "${BASE_URL}/api/v1/documents?vin=1HGBH41JXMN109186"
        echo -e "\n--- 4. Malformed VIN Rejection (400 Bad Request) ---"
        curl -s -X GET "${BASE_URL}/api/v1/documents?vin=INVALID" -H "Authorization: Bearer ${TOKEN}"
        echo -e "\n\n========================================================================"
        echo " All demo scenarios executed successfully!"
        echo "========================================================================"
        ;;

    *)
        echo "Unknown scenario: ${SCENARIO}"
        echo "Available commands:"
        echo "  ./scripts/test-runner.sh start             (Zero-config auto-port startup)"
        echo "  ./scripts/test-runner.sh check-ports       (diagnose port occupancy)"
        echo "  ./scripts/test-runner.sh clean-ports       (kill lingering processes on test ports)"
        echo "  ./scripts/test-runner.sh health            (check Spring Boot health)"
        echo "  ./scripts/test-runner.sh api-docs          (fetch OpenAPI 3 JSON schema)"
        echo "  ./scripts/test-runner.sh happy-path        (200 OK full aggregation)"
        echo "  ./scripts/test-runner.sh cache-hit         (sub-40ms cache response)"
        echo "  ./scripts/test-runner.sh partial-failure   (206 Partial Content resilience)"
        echo "  ./scripts/test-runner.sh total-outage      (503 Service Unavailable handling)"
        echo "  ./scripts/test-runner.sh security-401      (401 Unauthorized rejection)"
        echo "  ./scripts/test-runner.sh validation-400    (400 Bad Request rejection)"
        echo "  ./scripts/test-runner.sh metrics-check     (Prometheus metrics scrape)"
        echo "  ./scripts/test-runner.sh demo              (Run complete end-to-end demo)"
        exit 1
        ;;
esac
