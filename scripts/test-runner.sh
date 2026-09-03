#!/usr/bin/env bash
set -e

PORT=${PORT:-8085}
BASE_URL="http://localhost:${PORT}"
IDP_URL="http://localhost:8088"
SCENARIO=${1:-"all"}

echo "========================================================================"
echo " Unified Document Viewer Test Runner (Port: ${PORT})"
echo " Scenario: ${SCENARIO}"
echo "========================================================================"

get_token() {
    curl -s -X POST "${IDP_URL}/token" \
        -H "Content-Type: application/x-www-form-urlencoded" \
        -d "grant_type=client_credentials&client_id=operator-app&client_secret=secret&scope=openid" \
        | grep -o '"access_token":"[^"]*' | cut -d'"' -f4 || echo "mock-jwt-token-for-dev-and-testing"
}

case "${SCENARIO}" in
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
        docker compose stop service-stub
        sleep 1
        echo "[2/3] Querying with Service API offline (expecting 206 Partial Content)..."
        TOKEN=$(get_token)
        curl -i -s -X GET "${BASE_URL}/api/v1/documents?vin=1HGBH41JXMN109222" \
            -H "Authorization: Bearer ${TOKEN}"
        echo -e "\n[3/3] Restarting service-stub container..."
        docker compose start service-stub
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
        curl -s "${BASE_URL}/actuator/prometheus" | grep -E "(document_lookup|external_api|cache)"
        echo -e "\n--> Metrics check completed."
        ;;

    "all"|"demo")
        echo "Running full demo test suite..."
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
        echo "Available scenarios: happy-path, cache-hit, partial-failure, total-outage, security-401, validation-400, metrics-check, demo"
        exit 1
        ;;
esac
