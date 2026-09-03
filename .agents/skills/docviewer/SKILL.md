---
name: docviewer
description: >-
  Operational runbook, lifecycle commands, and test scenario suite for the Unified Document Viewer microservice.
  Use when the user asks to:
  - "Start app" or launch the application environment
  - "api docs" or "api-docs" to view the generated OpenAPI 3 specification
  - "tests", "test cases", or "demo" to list available test scenarios
  - "run test <scenario>" to execute specific live test scenarios (happy-path, cache-hit, partial-failure, total-outage, security-401, validation-400)
---

# Unified Document Viewer Skill

This skill provides step-by-step operational workflows and execution procedures for the Unified Document Viewer microservice.

---

## Command 1: Start App ("Start app" / "startup")

**Objective**: Ensure all backing containers (PostgreSQL, mock Sales API, mock Service API, mock IdP) and the Spring Boot application are built, running, and healthy.

### Execution Procedure:
1. **Start Backing Infrastructure**:
   ```bash
   docker compose up -d postgres sales-stub service-stub idp-mock
   ```
2. **Verify Container Health**:
   ```bash
   docker compose ps
   ```
   Confirm all 4 containers (`docviewer-postgres`, `docviewer-sales-stub`, `docviewer-service-stub`, `docviewer-idp-mock`) are `Up` and `healthy`.

3. **Check Port Availability**:
   Check if port 8080 or 8085 is available:
   ```bash
   lsof -i :8080 || lsof -i :8085
   ```
   If port 8080 is occupied by another process, start Spring Boot on port 8085 using `-Dserver.port=8085`.

4. **Build & Launch Spring Boot Application**:
   ```bash
   # In background/daemon mode:
   java -Dserver.port=8080 -jar target/unifieddocviewer-0.0.1-SNAPSHOT.jar || java -Dserver.port=8085 -jar target/unifieddocviewer-0.0.1-SNAPSHOT.jar
   ```

5. **Verify Readiness**:
   Poll the Actuator health endpoint until status is `UP`:
   ```bash
   curl -s http://localhost:8080/actuator/health || curl -s http://localhost:8085/actuator/health
   ```
   Expected response: `{"status":"UP", ...}`.

---

## Command 2: API Docs ("api docs" / "api-docs")

**Objective**: View the generated OpenAPI 3.0.1 schema and interactive documentation endpoints.

### Execution Procedure:
1. **Fetch Raw OpenAPI 3 JSON Schema**:
   ```bash
   curl -s http://localhost:8080/v3/api-docs || curl -s http://localhost:8085/v3/api-docs
   ```
2. **Interactive Swagger UI**:
   Open browser at: `http://localhost:8080/swagger-ui.html` (or `http://localhost:8085/swagger-ui.html`).

### API Summary:
- **`GET /api/v1/documents`**: Aggregates vehicle documents across registered dealership systems.
  - Query Parameter: `vin` (string, 17-character alphanumeric, required).
  - Header: `Authorization: Bearer <JWT>` (required).
  - Status Codes:
    - `200 OK`: All systems responded successfully.
    - `206 Partial Content`: At least one system responded, one or more degraded.
    - `400 Bad Request`: Malformed or missing VIN parameter.
    - `401 Unauthorized`: Missing or invalid JWT token.
    - `403 Forbidden`: Missing operator authorization.
    - `503 Service Unavailable`: All external systems failed.

---

## Command 3: List Test Cases / Demo ("tests" / "test cases" / "demo")

**Objective**: Present all automated test scenarios demonstrating how the system works.

| Scenario ID | Name | Trigger / Command | Expected Status | Description & Verification |
|---|---|---|---|---|
| `happy-path` | Full Aggregation Success | `run test happy-path` | `200 OK` | Queries both Sales & Service systems, merges documents, sorts by `createdAt DESC`. |
| `cache-hit` | Database Caching | `run test cache-hit` | `200 OK` | Repeat search returns in `<37ms` from PostgreSQL without outbound calls. |
| `partial-failure` | Fault Isolation & Partial Success | `run test partial-failure` | `206 Partial Content` | When Service API is down, returns available Sales docs with error diagnostics. |
| `total-outage` | All External Systems Down | `run test total-outage` | `503 Service Unavailable` | Gracefully returns 503 with per-source failure reasons without crashing. |
| `security-401` | Unauthorized Request | `run test security-401` | `401 Unauthorized` | Rejects requests without Authorization Bearer token. |
| `validation-400` | Malformed VIN Input | `run test validation-400` | `400 Bad Request` | Intercepts invalid VIN (e.g. `vin=INVALID`) before reaching backend. |
| `metrics-check` | Prometheus Metrics Scrape | `run test metrics-check` | `200 OK` | Verifies `document_lookup_total`, cache hits, and latency histograms. |

---

## Command 4: Run Test Scenario ("run test <scenario>")

Execute the specified test scenario against the active service:

### 1. `run test happy-path`
```bash
# 1. Acquire Bearer token
export JWT_TOKEN=$(curl -s -X POST http://localhost:8088/token -d "grant_type=client_credentials&client_id=operator-app&scope=openid" | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)

# 2. Execute Happy Path Search
curl -i -s -X GET "http://localhost:8080/api/v1/documents?vin=1HGBH41JXMN109186" -H "Authorization: Bearer $JWT_TOKEN"
# Verify: 200 OK, totalCount: 2, partialFailure: false
```

### 2. `run test cache-hit`
```bash
time curl -i -s -X GET "http://localhost:8080/api/v1/documents?vin=1HGBH41JXMN109186" -H "Authorization: Bearer $JWT_TOKEN"
# Verify: 200 OK, response time < 37ms, check logs for "Cache hit"
```

### 3. `run test partial-failure`
```bash
# 1. Stop Service System container
docker compose stop service-stub

# 2. Execute search
curl -i -s -X GET "http://localhost:8080/api/v1/documents?vin=1HGBH41JXMN109222" -H "Authorization: Bearer $JWT_TOKEN"
# Verify: 206 Partial Content, partialFailure: true, SALES: OK, SERVICE: ERROR

# 3. Restore Service System container
docker compose start service-stub
```

### 4. `run test total-outage`
```bash
curl -i -s -X GET "http://localhost:8080/api/v1/documents?vin=1HGBH41JXMN109999" -H "Authorization: Bearer $JWT_TOKEN"
# Verify: 503 Service Unavailable, partialFailure: true, all sources: ERROR
```

### 5. `run test security-401`
```bash
curl -i -s -X GET "http://localhost:8080/api/v1/documents?vin=1HGBH41JXMN109186"
# Verify: 401 Unauthorized
```

### 6. `run test validation-400`
```bash
curl -i -s -X GET "http://localhost:8080/api/v1/documents?vin=INVALID_SHORT_VIN" -H "Authorization: Bearer $JWT_TOKEN"
# Verify: 400 Bad Request
```

### 7. `run test metrics-check`
```bash
curl -s http://localhost:8080/actuator/prometheus | grep -E "(document_lookup|external_api|cache)"
# Verify: Counters and duration histograms are exposed
```
