# Unified Document Viewer

[![Java](https://img.shields.io/badge/Java-21%20LTS-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue.svg)](https://www.postgresql.org/)
[![Resilience4j](https://img.shields.io/badge/Resilience4j-2.2-yellow.svg)](https://resilience4j.readme.io/)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

> A high-performance, resilient document aggregation microservice designed to provide a unified view of all vehicle-related documents (sales contracts, service records, inspection reports, warranties) queried by **Vehicle Identification Number (VIN)** across distributed dealership systems.

---

## Table of Contents

- [Features](#features)
- [Architecture & Tech Stack](#architecture--tech-stack)
- [Prerequisites](#prerequisites)
- [Project Directory Structure](#project-directory-structure)
- [Configuration](#configuration)
- [Building the Application](#building-the-application)
- [Running the Application](#running-the-application)
  - [Method 1: One-Command Start (Docker Compose - Recommended)](#method-1-one-command-start-docker-compose---recommended)
  - [Method 2: Local Development Mode](#method-2-local-development-mode)
- [Testing Guide](#testing-guide)
  - [Automated Tests](#automated-tests)
  - [Manual API Testing with cURL](#manual-api-testing-with-curl)
  - [Testing Resilience & Circuit Breaker (Partial Failure)](#testing-resilience--circuit-breaker-partial-failure)
  - [Testing Cache Hit / Miss](#testing-cache-hit--miss)
  - [Testing Security & Authentication](#testing-security--authentication)
- [API Contract Reference](#api-contract-reference)
- [Observability & Monitoring](#observability--monitoring)
- [Extending to Additional External Systems](#extending-to-additional-external-systems)
- [AI Collaboration Narrative](#ai-collaboration-narrative)
- [Quick Commands & Agent Skills](#quick-commands--agent-skills)

---

## Features

- 🔍 **Unified Search**: Search documents by 17-character VIN across multiple external systems.
- ⚡ **Parallel Fan-Out**: Concurrent asynchronous querying via `CompletableFuture` and Java 21 Virtual Threads (`O(max(T_1, T_2))` response time).
- 🛡️ **Fault Tolerance & Self-Healing**: Resilience4j Circuit Breakers, TimeLimiters (3s timeout), and smart retries.
- 🧩 **Partial Success Support**: If an external system degrades or fails, available documents are returned (`206 Partial Content`) along with detailed per-source status.
- 🔐 **End-to-End JWT Security**:
  - **Inbound**: OAuth2 Resource Server validates user JWT tokens with Role-Based Access Control (`ROLE_OPERATOR`).
  - **Outbound**: Client-Credentials flow injects service-account JWTs for calls to external APIs with automatic caching and renewal.
- 💾 **Intelligent Persistence Cache**: PostgreSQL stores aggregated documents with configurable TTL (default 60s) to minimize redundant external calls.
- 🔌 **Extensible Plugin Architecture**: Open/Closed design (`ExternalSystemClientRegistry`) allowing new external systems to be added via configuration and a single interface implementation with zero core modifications.
- 📊 **Full Observability**: Micrometer metrics, structured JSON logging with MDC correlation IDs (`traceId`, `spanId`), and Prometheus Actuator endpoints.

---

## Architecture & Tech Stack

```
[ Web Client / Browser ] 
          │  HTTPS + Bearer <User-JWT>
          ▼
   [ API Gateway / Nginx ]
          │
          ▼
[ Unified Document Viewer (Spring Boot 3.3 / Java 21) ]
  ├── Spring Security (JWT / RS256 JWKS validation)
  ├── DocumentController (REST API)
  ├── DocumentAggregationService (Virtual Threads Fan-out)
  ├── ExternalSystemClientRegistry
  │     ├── SalesSystemClient (WebClient + Resilience4j + Service-JWT)
  │     ├── ServiceSystemClient (WebClient + Resilience4j + Service-JWT)
  │     └── [Future Clients: Finance, Parts, etc.]
  └── DocumentRepository (Spring Data JPA)
          │
          ├──► [ PostgreSQL 16 ] (Cache & Audit Store)
          ├──► [ Sales System API ] (Mock Stub / WireMock)
          └──► [ Service System API ] (Mock Stub / WireMock)
```

| Component | Technology |
|---|---|
| **Runtime & Language** | Java 21 (LTS) with Virtual Threads (Project Loom) |
| **Framework** | Spring Boot 3.3.x (Spring MVC, Spring Data JPA, Spring Security OAuth2) |
| **HTTP Client** | Spring WebClient (Reactor Netty) |
| **Resilience** | Resilience4j (CircuitBreaker, TimeLimiter, Retry) |
| **Database** | PostgreSQL 16 + HikariCP |
| **API Spec & Docs** | SpringDoc OpenAPI 3.x (Swagger UI) |
| **Security** | OAuth2 Resource Server (Inbound) + OAuth2 Client Credentials (Outbound) |
| **Testing** | JUnit 5, Mockito, WireMock, Testcontainers |
| **Observability** | Micrometer, Spring Boot Actuator, Prometheus |

---

## Prerequisites

Ensure you have the following installed on your local workstation:

- **JDK 21** (e.g., Eclipse Temurin, Amazon Corretto, or OpenJDK 21)
- **Apache Maven 3.9+** (or use the included `./mvnw` wrapper)
- **Docker** and **Docker Compose v2+**
- **cURL** or **Postman** (for manual API testing)

Check your environment versions:
```bash
java -version
mvn -version
docker --version
docker compose version
```

---

## Project Directory Structure

```
unifieddocviewer/
├── pom.xml                                 # Maven dependencies and build configuration
├── README.md                               # Application build, run, and test guide
├── system-design.md                        # Full system architecture and design document
├── docker-compose.yml                      # Multi-container orchestration (App, Postgres, Stubs, IdP)
├── config/
│   └── application.yml                     # Central application configuration
├── stubs/                                  # Mock external services & WireMock mappings
│   ├── sales-system/
│   └── service-system/
└── src/
    ├── main/
    │   ├── java/com/xuanvolab/unifieddocviewer/
    │   │   ├── UnifiedDocViewerApplication.java
    │   │   ├── config/                     # WebClient, Resilience4j, Executor configs
    │   │   ├── controller/                 # REST API endpoints (DocumentController)
    │   │   ├── model/                      # JPA Entities and DTOs
    │   │   ├── repository/                 # Spring Data JPA repositories
    │   │   ├── security/                   # JwtAuthenticationFilter & Outbound Interceptor
    │   │   └── service/                    # Aggregation orchestrator & external client registry
    │   │       └── client/                 # ExternalSystemClient implementations
    │   └── resources/
    │       ├── application.yml
    │       ├── db/migration/               # Flyway / Liquibase database schema scripts
    │       └── logback-spring.xml          # Structured JSON logging configuration
    └── test/
        ├── java/com/xuanvolab/unifieddocviewer/
        │   ├── unit/                       # Unit tests (Services, Controllers, Clients)
        │   └── integration/                # Integration tests (WireMock, Testcontainers)
        └── resources/
            └── application-test.yml
```

---

## Configuration

The primary configuration is managed in `src/main/resources/application.yml` (and overridden via environment variables):

```yaml
server:
  port: 8080

spring:
  application:
    name: unified-document-viewer
  threads:
    virtual:
      enabled: true                          # Enable Java 21 Virtual Threads
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:docviewer}
    username: ${DB_USER:docuser}
    password: ${DB_PASS:docpassword}
    hikari:
      maximum-pool-size: 20
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${JWT_ISSUER_URI:http://localhost:8088/realms/dealership}
          jwk-set-uri: ${JWKS_URI:http://localhost:8088/realms/dealership/protocol/openid-connect/certs}

# External Systems Registration (Extensible Plugin Configuration)
external-systems:
  cache-ttl-seconds: 60
  systems:
    sales:
      enabled: true
      source-name: SALES
      base-url: ${SALES_API_URL:http://localhost:8081}
      timeout-ms: 3000
      token-uri: ${IDP_TOKEN_URL:http://localhost:8088/token}
      client-id: unified-viewer-svc
      client-secret: ${SALES_CLIENT_SECRET:sales-secret-key}
      scope: sales:documents:read
    service:
      enabled: true
      source-name: SERVICE
      base-url: ${SERVICE_API_URL:http://localhost:8082}
      timeout-ms: 3000
      token-uri: ${IDP_TOKEN_URL:http://localhost:8088/token}
      client-id: unified-viewer-svc
      client-secret: ${SERVICE_CLIENT_SECRET:service-secret-key}
      scope: service:documents:read

# Resilience4j Circuit Breaker & TimeLimiter
resilience4j:
  circuitbreaker:
    instances:
      sales:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 3
      service:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 3
  timelimiter:
    instances:
      sales:
        timeoutDuration: 3s
      service:
        timeoutDuration: 3s
```

---

## Building the Application

### 1. Compile and Package with Maven

Run the standard Maven lifecycle command:

```bash
# Clean, compile, run all tests, and package executable JAR
mvn clean package

# Build while skipping automated test suite (for fast builds)
mvn clean package -DskipTests
```

The resulting executable artifact will be generated at:
```
target/unifieddocviewer-0.0.1-SNAPSHOT.jar
```

### 2. Build Docker Image

```bash
docker build -t unified-document-viewer:latest .
```

---

## Running the Application

### Method 1: Full-Stack Docker Compose (Recommended)

This starts the complete environment: PostgreSQL, Mock Sales API, Mock Service API, Mock IdP, and the Spring Boot application container.

```bash
# 1. Start all containers including the application
docker compose --profile full-stack up -d --build

# 2. Check health status of all running services
docker compose ps

# 3. View live application logs
docker compose logs -f app
```

Once running, the following endpoints are accessible:
- **Unified Document Viewer API**: `http://localhost:8080/api/v1/documents?vin=1HGBH41JXMN109186`
- **Swagger / OpenAPI UI**: `http://localhost:8080/swagger-ui.html`
- **Actuator Health**: `http://localhost:8080/actuator/health`
- **Prometheus Metrics**: `http://localhost:8080/actuator/prometheus`
- **Mock Sales System API**: `http://localhost:8081`
- **Mock Service System API**: `http://localhost:8082`
- **Mock Identity Provider**: `http://localhost:8088`

To stop and remove all containers and volumes:
```bash
docker compose --profile full-stack down -v
```

---

### Method 2: Local Development Mode

Start the backing infrastructure in Docker and run the Spring Boot service locally for rapid iterative development and debugging:

1. **Start infrastructure dependencies (PostgreSQL, Stubs, IdP)**:
   ```bash
   docker compose up -d postgres sales-stub service-stub idp-mock
   ```

2. **Run Spring Boot application**:
   ```bash
   mvn spring-boot:run
   ```
   *Or launch `UnifiedDocViewerApplication.java` from your IDE.*

---

## Agent / Developer End-to-End Verification Runbook

Follow these exact steps to start up the environment and verify that every feature works as designed.

### Step 1: Start Supporting Services
```bash
# Start backing infrastructure
docker compose up -d postgres sales-stub service-stub idp-mock

# Verify all stubs and database are running
docker compose ps
```

### Step 2: Build & Start Spring Boot Service
```bash
# Compile and run in background or separate terminal
mvn spring-boot:run
```

Wait until the log displays:
```
Started UnifiedDocViewerApplication in X.XXX seconds
```

### Step 3: Verify Health Endpoint
```bash
curl -s http://localhost:8080/actuator/health
# Expected Output: {"status":"UP","components":{"circuitBreakers":{"status":"UP"},"db":{"status":"UP",...},...}}
```

### Step 4: Obtain OAuth2 Bearer Token
```bash
export JWT_TOKEN=$(curl -s -X POST http://localhost:8088/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=operator-app&client_secret=operator-secret&scope=openid" \
  | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)

echo "Acquired Token: $JWT_TOKEN"
```

### Step 5: Verify Full Aggregation (Happy Path — 200 OK)
```bash
curl -i -X GET "http://localhost:8080/api/v1/documents?vin=1HGBH41JXMN109186" \
  -H "Authorization: Bearer $JWT_TOKEN"
```
**Verification checklist**:
- HTTP status: `200 OK`
- `totalCount`: `2`
- `partialFailure`: `false`
- `sources`: Contains both `SALES` (count: 1) and `SERVICE` (count: 1) with `status: "OK"`
- `documents`: Contains 2 items sorted by `createdAt DESC`

### Step 6: Verify PostgreSQL Cache Hit
Run the same search query a second time:
```bash
curl -i -X GET "http://localhost:8080/api/v1/documents?vin=1HGBH41JXMN109186" \
  -H "Authorization: Bearer $JWT_TOKEN"
```
**Verification checklist**:
- Response returns immediately (`< 20ms`).
- Application logs indicate: `Cache hit for VIN: 1HGBH41JXMN109186. Found 2 cached documents`.

### Step 7: Verify Partial Failure & Circuit Breaker (206 Partial Content)
```bash
# 1. Stop the Service System stub to simulate external failure
docker compose stop service-stub

# 2. Query documents with Service System offline
curl -i -X GET "http://localhost:8080/api/v1/documents?vin=1HGBH41JXMN109186" \
  -H "Authorization: Bearer $JWT_TOKEN"
```
**Verification checklist**:
- HTTP status: `206 Partial Content`
- `partialFailure`: `true`
- `sources`: `SALES` is `OK` (count: 1); `SERVICE` is `ERROR` or `TIMEOUT` (count: 0)
- `documents`: Contains Sales documents without crashing

```bash
# 3. Restore the Service System stub
docker compose start service-stub
```

### Step 8: Verify Security & Input Validation
```bash
# 1. Missing Token -> 401 Unauthorized
curl -i -X GET "http://localhost:8080/api/v1/documents?vin=1HGBH41JXMN109186"
# Expected: HTTP/1.1 401 Unauthorized

# 2. Invalid VIN -> 400 Bad Request
curl -i -X GET "http://localhost:8080/api/v1/documents?vin=INVALID" \
  -H "Authorization: Bearer $JWT_TOKEN"
# Expected: HTTP/1.1 400 Bad Request
```

### Step 9: Run Automated Test Suite
```bash
mvn clean test
# Expected: Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS
```

---

## Testing Guide

### Automated Tests

The codebase includes unit and integration tests using JUnit 5, Mockito, WireMock, and H2/PostgreSQL.

```bash
# Run all unit and integration tests
mvn test

# Run with test coverage (JaCoCo)
mvn test jacoco:report
```

---

### Manual API Testing with cURL

#### Step 1: Obtain a JWT Bearer Token

For local testing against the mock IdP:
```bash
export JWT_TOKEN=$(curl -s -X POST http://localhost:8088/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=operator-app&client_secret=operator-secret&scope=openid" \
  | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)

# Verify token received
echo "Token: $JWT_TOKEN"
```

---

#### Step 2: Query Documents for a VIN (Happy Path — 200 OK)

Query for a known vehicle:
```bash
curl -i -X GET "http://localhost:8080/api/v1/documents?vin=1HGBH41JXMN109186" \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Accept: application/json"
```

**Expected Response (`200 OK`)**:
```json
{
  "vin": "1HGBH41JXMN109186",
  "totalCount": 2,
  "partialFailure": false,
  "sources": [
    { "system": "SALES", "status": "OK", "documentCount": 1 },
    { "system": "SERVICE", "status": "OK", "documentCount": 1 }
  ],
  "documents": [
    {
      "documentId": "SAL-10042",
      "sourceSystem": "SALES",
      "documentType": "PURCHASE_ORDER",
      "title": "Vehicle Purchase Order",
      "createdAt": "2024-03-15T09:00:00Z",
      "documentUrl": "https://storage.example.com/docs/SAL-10042.pdf?X-Signature=abc&X-Expires=1725360000",
      "metadata": {
        "amount": 35000,
        "currency": "USD"
      }
    },
    {
      "documentId": "SVC-88821",
      "sourceSystem": "SERVICE",
      "documentType": "SERVICE_RECORD",
      "title": "60,000 Mile Service",
      "createdAt": "2025-11-20T14:30:00Z",
      "documentUrl": "https://storage.example.com/docs/SAL-10042.pdf?X-Signature=abc&X-Expires=1725360000",
      "metadata": {
        "technician": "J. Smith",
        "mileage": 60142
      }
    }
  ]
}
```

---

### Testing Resilience & Circuit Breaker (Partial Failure)

Simulate a scenario where the **Service System** is unavailable or timing out:

1. **Simulate Service System Failure**:
   ```bash
   # Configure mock stub to return 500 or stop the service container
   docker compose stop service-stub
   ```

2. **Execute Document Search**:
   ```bash
   curl -i -X GET "http://localhost:8080/api/v1/documents?vin=1HGBH41JXMN109186" \
     -H "Authorization: Bearer $JWT_TOKEN"
   ```

3. **Verify Degraded / Partial Success Response (`206 Partial Content`)**:
   ```json
   {
     "vin": "1HGBH41JXMN109186",
     "totalCount": 1,
     "partialFailure": true,
     "sources": [
       { "system": "SALES", "status": "OK", "documentCount": 1 },
       { "system": "SERVICE", "status": "ERROR", "documentCount": 0, "message": "Service System is temporarily unavailable." }
     ],
     "documents": [
       {
         "documentId": "SAL-10042",
         "sourceSystem": "SALES",
         "documentType": "PURCHASE_ORDER",
         "title": "Vehicle Purchase Order",
         "createdAt": "2024-03-15T09:00:00Z",
         "documentUrl": "https://storage.example.com/docs/SAL-10042.pdf?X-Signature=abc&X-Expires=1725360000"
       }
     ]
   }
   ```

4. **Verify Circuit Breaker Trip**:
   Send 10 requests rapidly to trip the circuit breaker. Subsequent calls will fail fast (`status: "CIRCUIT_OPEN"`) in `< 1ms` without making any network calls.

5. **Restore the Stub**:
   ```bash
   docker compose start service-stub
   ```
   After the 30-second `waitDurationInOpenState`, the circuit breaker will enter `HALF-OPEN`, probe the restored service, and transition back to `CLOSED`.

---

### Testing Cache Hit / Miss

The service caches aggregated results in PostgreSQL with a configurable TTL (default 60s):

1. **First Request (Cache Miss)**:
   ```bash
   time curl -s -X GET "http://localhost:8080/api/v1/documents?vin=1HGBH41JXMN109186" \
     -H "Authorization: Bearer $JWT_TOKEN" > /dev/null
   # Expected latency: ~150-250ms (network fan-out)
   ```

2. **Second Request (Cache Hit)**:
   ```bash
   time curl -s -X GET "http://localhost:8080/api/v1/documents?vin=1HGBH41JXMN109186" \
     -H "Authorization: Bearer $JWT_TOKEN" > /dev/null
   # Expected latency: ~5-15ms (direct database lookup)
   ```

3. **Check Prometheus Cache Metrics**:
   ```bash
   curl -s http://localhost:8080/actuator/prometheus | grep cache_
   ```

---

### Testing Security & Authentication

#### 1. Missing Token (401 Unauthorized)
```bash
curl -i -X GET "http://localhost:8080/api/v1/documents?vin=1HGBH41JXMN109186"
# HTTP/1.1 401 Unauthorized
```

#### 2. Invalid / Expired Token (401 Unauthorized)
```bash
curl -i -X GET "http://localhost:8080/api/v1/documents?vin=1HGBH41JXMN109186" \
  -H "Authorization: Bearer invalid-token-string"
# HTTP/1.1 401 Unauthorized
```

#### 3. Invalid VIN Format (400 Bad Request)
```bash
curl -i -X GET "http://localhost:8080/api/v1/documents?vin=INVALID_SHORT_VIN" \
  -H "Authorization: Bearer $JWT_TOKEN"
# HTTP/1.1 400 Bad Request
# {"error": "Invalid VIN format. Must be a 17-character alphanumeric string."}
```

---

## API Contract Reference

### `GET /api/v1/documents`

Aggregates documents for a specific VIN from all registered external systems.

#### Parameters
| Name | In | Type | Required | Description |
|---|---|---|---|---|
| `vin` | query | `string` | Yes | 17-character alphanumeric Vehicle Identification Number |

#### Headers
| Name | Value | Required | Description |
|---|---|---|---|
| `Authorization` | `Bearer <JWT>` | Yes | User OAuth2 access token with `ROLE_OPERATOR` |
| `Accept` | `application/json` | Yes | MIME type |

#### HTTP Response Status Codes
| Status | Condition | Description |
|---|---|---|
| `200 OK` | All external systems succeeded | Complete list of aggregated documents |
| `206 Partial Content` | ≥1 system succeeded, ≥1 failed/timed out | Partial list with per-source degradation breakdown |
| `400 Bad Request` | Missing or malformed VIN | Validation error message |
| `401 Unauthorized` | Missing, invalid, or expired JWT | Authentication failure |
| `403 Forbidden` | Valid JWT without `ROLE_OPERATOR` | Authorization denial |
| `404 Not Found` | No documents found across any source | Empty search result |
| `503 Service Unavailable` | All external systems failed | Upstream outage error |

---

## Observability & Monitoring

### Spring Boot Actuator Endpoints

| Endpoint | Description |
|---|---|
| `http://localhost:8080/actuator/health` | Service liveness, readiness, DB status, and circuit breakers |
| `http://localhost:8080/actuator/metrics` | Available JVM and application metrics |
| `http://localhost:8080/actuator/prometheus` | Prometheus-formatted metric scrape target |

### Custom Micrometer Metrics

| Metric Name | Type | Description |
|---|---|---|
| `document_lookup_total` | Counter | Total document search requests (tagged by status) |
| `document_lookup_duration_ms` | Histogram | End-to-end lookup latency distribution |
| `external_api_call_duration_ms` | Histogram | Per-system outbound latency (`source_system="SALES"`) |
| `external_api_errors_total` | Counter | Outbound errors tagged by `source_system` and `error_type` |
| `circuit_breaker_state` | Gauge | Circuit breaker state (`0=CLOSED`, `1=OPEN`, `2=HALF_OPEN`) |
| `cache_hits_total` / `cache_misses_total` | Counter | Cache performance counters |

---

## Extending to Additional External Systems

The system is designed under the **Open/Closed Principle**. To add a 3rd or 4th external source (e.g., **Finance System** or **Warranty System**):

1. **Implement `ExternalSystemClient`**:
   ```java
   @Component
   public class FinanceSystemClient implements ExternalSystemClient {
       @Override
       public String getSourceSystem() {
           return "FINANCE";
       }

       @Override
       public List<ExternalDocument> fetchDocuments(String vin, String jwtToken) {
           // WebClient call to finance API
       }
   }
   ```

2. **Add System Configuration in `application.yml`**:
   ```yaml
   external-systems:
     systems:
       finance:
         enabled: true
         source-name: FINANCE
         base-url: https://finance-api.example.com
         timeout-ms: 3000
         token-uri: https://idp.example.com/token
         client-id: unified-viewer-svc
         client-secret: ${FINANCE_CLIENT_SECRET}
         scope: finance:documents:read
   ```

---

## AI Collaboration Narrative

### 1. How I guided the AI

To maximize the AI's utility and prevent hallucinated or substandard patterns, I established a **context-driven, constraint-first prompting strategy**:

- **Role & Standard Anchoring**: Assigned the AI the role of a Principal Distributed Systems Architect, requiring every proposal to adhere strictly to both core and non-functional requirements.

- **Clear Objective & Direction**: Instructed the AI to seek clarification on ambiguities to prevent hallucinations, and ensured all AI proposals were reviewed and evaluated before moving to the next step.

- **Constrain & Refine**: Introduced specific constraints to guide the AI toward optimal solutions (e.g., "use only layered architecture," "add circuit breakers," "handle edge cases").

### 2. How I Verified and Refined the AI's Output

I reviewed and tested all AI-generated suggestions before incorporating them into the project:

- **Evaluating the Architecture**: Instructed the AI to use only a layered architecture rather than other patterns for implementing a simple application, making it easier to understand, test, and maintain.

- **Adding Missing Components**:
    - Prompted the AI to add a Circuit Breaker to prevent cascading failures when external systems go down.
    - Required the AI to handle edge cases, such as providing partial responses to clients when external systems fail, rather than returning a hard error.

- **Automating Testing**: Directed the AI to write test cases to verify system behavior instead of relying on manual testing, and asked it to create a script to execute the test suite automatically.

### 3. How I Ensured Final Code Quality

- **Automated Testing**: I added 13 unit and integration tests (`mvn clean test`) covering successful searches, partial failures, invalid VINs, and security checks.
- **Testing Real Failure Scenarios**: I set up Docker Compose with WireMock to test slow responses, 500 errors, and circuit breaker trip conditions.
- **Checking Thread Safety**: I reviewed the token caching logic to make sure there are no race conditions when multiple requests run concurrently.
- **One-Click Test Script**: I created `scripts/test-runner.sh` so anyone can test and verify all scenarios with simple commands.

---

## Quick Commands & Agent Skills

This repository includes an integrated Antigravity Agent Skill located at [`.agents/skills/docviewer/SKILL.md`](file:///.agents/skills/docviewer/SKILL.md) and an automated test runner script [`scripts/test-runner.sh`](file:///scripts/test-runner.sh).

Any new developer or AI assistant can immediately interact with the repository using natural commands:

| Natural Language Request | Action Performed | CLI Equivalent |
|---|---|---|
| **`Start app`** / `startup` | Starts Docker infrastructure (PostgreSQL, mock Sales/Service APIs, mock IdP), builds the project, launches Spring Boot, and verifies `UP` health. | `docker compose up -d && mvn spring-boot:run` |
| **`api docs`** / `api-docs` | Displays the OpenAPI 3 schema and lists all endpoints, query parameters, headers, and responses. | `curl -s http://localhost:8080/v3/api-docs` |
| **`tests`** / `test cases` / `demo` | Lists all test scenarios and executes the full automated demonstration suite. | `./scripts/test-runner.sh demo` |
| **`run test <scenario>`** | Executes a specific live test scenario against the running service. | `./scripts/test-runner.sh <scenario>` |

### Available Test Scenarios:
- `./scripts/test-runner.sh happy-path`: Queries VIN `1HGBH41JXMN109186`, verifies `200 OK` and merged document list.
- `./scripts/test-runner.sh cache-hit`: Verifies instant `<37ms` response from PostgreSQL cache.
- `./scripts/test-runner.sh partial-failure`: Shuts down Service stub, verifies `206 Partial Content` fault isolation, and restores the stub.
- `./scripts/test-runner.sh total-outage`: Verifies graceful `503 Service Unavailable` handling when all upstream systems fail.
- `./scripts/test-runner.sh security-401`: Verifies rejection of unauthenticated requests.
- `./scripts/test-runner.sh validation-400`: Verifies rejection of malformed VIN inputs.
- `./scripts/test-runner.sh metrics-check`: Inspects Prometheus Actuator counters and histograms.

---

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

