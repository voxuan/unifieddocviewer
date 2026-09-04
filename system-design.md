# System Design Document — Unified Document Viewer

> **Author**: Xuan Vo  
> **Date**: 2026-09-03  
> **Version**: 1.1  
> **Status**: Draft

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture Diagram](#architecture-diagram)
3. [Component Descriptions](#component-descriptions)
4. [Security Architecture](#security-architecture)
5. [Data Flow](#data-flow)
6. [Technology Choices & Justifications](#technology-choices--justifications)
7. [API Contract](#api-contract)
8. [Data Models](#data-models)
9. [Observability Strategy](#observability-strategy)
10. [Non-Functional Considerations](#non-functional-considerations)
11. [GenAI Usage](#genai-usage)

---

## Overview

The **Unified Document Viewer** is a full-stack web application that gives operators a single interface to search for all documents associated with a vehicle (identified by its **VIN — Vehicle Identification Number**). It aggregates documents from two or more independent dealership back-end systems:

- **Sales System API** — manages sales contracts, purchase orders, invoices, warranties, etc.
- **Service System API** — manages service records, repair orders, inspection reports, etc.
- *(Future)* **Finance System API**, **Parts System API**, etc. — the architecture is designed to accommodate additional sources without modifying core logic.

The backend handles the fan-out to all registered systems in parallel, merges the results, and exposes a clean, unified API to the frontend. All communication — inbound from users and outbound to external systems — is secured with **JWT (JSON Web Tokens)**.

---

## Architecture Diagram

```
┌──────────────────────────────────────────────────────────────────────────┐
│                              CLIENT LAYER                                │
│                                                                          │
│   ┌──────────────────────────────────────────────────────────────────┐   │
│   │              Web Browser  (React SPA / Static HTML)              │   │
│   │                          VIN Search UI                           │   │
│   └──────────────────────────────┬───────────────────────────────────┘   │
│                                  │  HTTPS + Authorization: Bearer <JWT>  │
└──────────────────────────────────┼───────────────────────────────────────┘
                                   │
               ┌───────────────────▼───────────────────┐
               │      Identity Provider (IdP)          │
               │   (Keycloak / Auth0 / Okta)           │
               │   - Issues JWT access tokens          │
               │   - JWKS endpoint for public keys     │
               └───────────────────┬───────────────────┘
                                   │  (key verification)
                    ┌──────────────▼──────────────┐
                    │      API Gateway / Nginx    │  TLS termination,
                    │      (Reverse Proxy)        │  rate limiting
                    └──────────────┬──────────────┘
                                   │  JWT forwarded in Authorization header
          ┌────────────────────────▼───────────────────────────────┐
          │             Unified Document Viewer Service            │
          │                  (Spring Boot Application)             │
          │                                                        │
          │  ┌──────────────────────────────────────────────────┐  │
          │  │  Spring Security Filter Chain                    │  │
          │  │  - JwtAuthenticationFilter (validates JWT)       │  │
          │  │  - Extracts claims: userId, roles, sub           │  │
          │  └───────────────────────┬──────────────────────────┘  │
          │                          │                             │
          │  ┌───────────────────────▼──────────────────────────┐  │
          │  │   DocumentController  (REST Endpoint)            │  │
          │  └───────────────────────┬──────────────────────────┘  │
          │                          │                             │
          │  ┌───────────────────────▼──────────────────────────┐  │
          │  │   DocumentAggregationService                     │  │
          │  │   (parallel fan-out over registered clients)     │  │
          │  └──────┬──────────────────────┬────────────────┬───┘  │
          │         │                      │                │      │
          │  ┌──────▼──────┐  ┌────────────▼────────┐   ┌───▼──┐   │
          │  │ SalesClient │  │ ServiceClient       │   │ ...  │   │
          │  │ (JWT header)│  │ (JWT header)        │   │(N+1) │   │
          │  └──────┬──────┘  └────────────┬────────┘   └───┬──┘   │
          │         │    ExternalSystemClientRegistry       │      │
          │  ┌──────▼───────────────────────────────────────▼───┐  │
          │  │              JwtOutboundInterceptor              │  │
          │  │  (injects service-account JWT into each request) │  │
          │  └──────────────────────────────────────────────────┘  │
          │                                                        │
          │  ┌──────────────────────────────────────────────────┐  │
          │  │   DocumentRepository  (JPA / PostgreSQL cache)   │  │
          │  └──────────────────────────────────────────────────┘  │
          │                                                        │
          │  ┌──────────────────────────────────────────────────┐  │
          │  │   Observability Stack                            │  │
          │  │   Micrometer -> Prometheus -> Grafana            │  │
          │  │   Logback -> ELK / Loki                          │  │
          │  │   Micrometer Tracing (OpenTelemetry / Brave)     │  │
          │  └──────────────────────────────────────────────────┘  │
          └────────────────────────────┬───────────────────────────┘
                                       │  Authorization: Bearer <service-JWT>
          ┌────────────────────────────┼────────────────────────────┐
          │                            │                            │
┌─────────▼──────────┐   ┌─────────────▼────────┐   ┌───────────────▼──────┐
│  Sales System API  │   │ Service System API   │   │  Future System API   │
│  (Mocked Stub)     │   │ (Mocked Stub)        │   │  (plug in via config)│
└────────────────────┘   └──────────────────────┘   └──────────────────────┘
                                       │
                          ┌────────────▼────────────┐
                          │     PostgreSQL 16       │
                          │  (cache + audit store)  │
                          └─────────────────────────┘
```

---

## Component Descriptions

### 1. Web Frontend (Client Layer)

| Attribute | Detail |
|-----------|--------|
| **Technology** | React (TypeScript) or a minimal HTML/JS page |
| **Responsibility** | Renders the VIN search input, submits the query to the backend, and displays the unified document list with source-system badges |
| **Deployment** | Served as static assets by Nginx or embedded Spring Boot resource |

The UI is intentionally thin — all business logic lives in the backend.

---

### 2. API Gateway / Nginx (Reverse Proxy)

| Attribute | Detail |
|-----------|--------|
| **Technology** | Nginx or Spring Cloud Gateway |
| **Responsibility** | TLS termination, rate limiting, request routing to the backend service, CORS headers |
| **Why it exists** | Decouples the public-facing network concerns from the application layer; allows the backend service to scale horizontally |

---

### 3. Unified Document Viewer Service (Spring Boot)

This is the **core** of the system and owns three internal sub-layers:

#### 3a. `DocumentController` (Presentation / API Layer)
- Exposes `GET /api/v1/documents?vin={vin}` REST endpoint.
- Validates the VIN format (17-character alphanumeric).
- Returns a `DocumentAggregateResponse` JSON payload.
- Delegates all processing to `DocumentAggregationService`.

#### 3b. `DocumentAggregationService` (Business / Orchestration Layer)
- Receives a validated VIN.
- **Fan-out**: Fires two non-blocking HTTP calls **in parallel** using `CompletableFuture.allOf()` + a dedicated `ExecutorService`.
- **Merge**: Collects results from both clients, annotates each document with its `sourceSystem` tag, and composes the unified list.
- **Resilience**: Wraps each external call with a **Resilience4j** `CircuitBreaker` + `TimeLimiter`; on partial failure (one system down), returns available documents with a partial-failure flag.
- **Cache**: Checks/writes `DocumentRepository` for caching with a configurable TTL (e.g., 60 s).

#### 3c. External System Clients — Extensible Registry Pattern

To support **2 external systems today and N systems in the future**, all external clients implement a common interface and are registered in a central registry. Adding a new system requires only a new implementation class and a configuration entry — **zero changes to core aggregation logic**.

```
ExternalSystemClient  (interface)
├── SalesSystemClient      implements ExternalSystemClient
├── ServiceSystemClient    implements ExternalSystemClient
└── FinanceSystemClient    implements ExternalSystemClient  ← future, plug in via config

ExternalSystemClientRegistry
└── List<ExternalSystemClient>  (auto-wired by Spring — all beans of this type)
```

**`ExternalSystemClient` Interface**
```java
public interface ExternalSystemClient {
    String getSourceSystem();          // e.g. "SALES", "SERVICE"
    List<ExternalDocument> fetchDocuments(String vin, String jwtToken);
}
```

`DocumentAggregationService` iterates over the registry:
```java
List<CompletableFuture<List<Document>>> futures = registry.getAll().stream()
    .map(client -> supplyAsync(() -> client.fetchDocuments(vin, jwt), executor))
    .toList();
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
```

**Each client** is wired with its own configurable base URL, timeout, and Resilience4j `CircuitBreaker`. A new external system is onboarded by:
1. Adding a new `@Component` class implementing `ExternalSystemClient`.
2. Adding its `base-url`, `timeout`, and `jwt` config block to `application.yml`.
3. No changes to `DocumentAggregationService` or any other existing class.

---

### 4. `DocumentRepository` (Persistence Layer)

| Attribute | Detail |
|-----------|--------|
| **Technology** | Spring Data JPA + PostgreSQL |
| **Responsibility** | Persists the aggregated document list per VIN + timestamp; used as a short-lived cache and for audit/history queries |
| **TTL strategy** | A scheduled job (`@Scheduled`) evicts records older than the configured TTL |

Persisting results allows:
- Faster repeat lookups (cache hit avoids external calls).
- Auditing and debugging (what was shown to the user and when).
- Future analytics (document volume per VIN, system availability).

---

### 5. Mocked External APIs (Sales & Service System Stubs)

| Attribute | Detail |
|-----------|--------|
| **Technology** | Separate Spring Boot applications (or WireMock standalone) |
| **Responsibility** | Return deterministic, realistic JSON payloads for known VINs; simulate latency and failure modes |
| **Usage** | Used in development, integration tests, and demos |

Each stub implements the agreed API contract (see [API Contract](#api-contract)) and can be seeded with fixture data files.

---

### 6. PostgreSQL Database

| Attribute | Detail |
|-----------|--------|
| **Technology** | PostgreSQL 16 |
| **Responsibility** | Durable storage for aggregated document records |
| **Schema** | Single `documents` table with `vin`, `source_system`, `document_type`, `document_id`, `metadata` (JSONB), `fetched_at` |

---

## Security Architecture

Security operates at **two boundaries**: inbound (client-to-service) and outbound (service-to-external-APIs). Both use JWT.

### Inbound Security — Client → Unified Service

```
Browser / API Client
  |-- POST /auth/token --> Identity Provider (Keycloak / Auth0)
  |                        <-- JWT access token (signed RS256)
  |
  |-- GET /api/v1/documents?vin=...
  |   Authorization: Bearer <access_token>
  |
  v
Spring Security JwtAuthenticationFilter
  1. Extract Bearer token from Authorization header.
  2. Fetch JWKS from IdP's /.well-known/jwks.json (cached).
  3. Validate JWT signature (RS256), expiry, issuer, audience.
  4. Populate SecurityContext with Authentication (userId, roles).
  5. Reject with 401 Unauthorized if invalid or expired.
```

**JWT Claims used by the service**

| Claim | Usage |
|-------|-------|
| `sub` | User identifier (logged for audit trail) |
| `roles` | `ROLE_OPERATOR` required to access document endpoints |
| `exp` | Token expiry enforced by Spring Security |
| `iss` | Validated against configured trusted issuer URI |
| `aud` | Validated against `unified-document-viewer` audience |

**Spring Security configuration** (`SecurityFilterChain`):
```java
http
  .oauth2ResourceServer(oauth2 ->
      oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(customConverter())))
  .authorizeHttpRequests(auth -> auth
      .requestMatchers("/actuator/health").permitAll()
      .requestMatchers("/api/v1/**").hasRole("OPERATOR")
      .anyRequest().authenticated());
```

---

### Outbound Security — Unified Service → External APIs

Each external system requires the caller to present a valid JWT service-account token.
The Unified Service uses a **client-credentials OAuth2 flow** to obtain a service-scoped token and injects it into every outbound request via a shared `JwtOutboundInterceptor`.

```
DocumentAggregationService
  |
  |-- calls ExternalSystemClient.fetchDocuments(vin, jwtToken)
  |
  v
JwtOutboundInterceptor  (WebClient ExchangeFilterFunction)
  1. Check in-memory token cache — is service-account JWT still valid?
  2. CACHE HIT  --> use cached token.
  3. CACHE MISS --> POST /token to IdP (client_credentials grant).
                   Cache new token until (exp - 30s buffer).
  4. Inject header:  Authorization: Bearer <service-account-jwt>
  |
  v
External System API
  - Validates the incoming JWT.
  - Returns documents or 401/403.
```

**Per-system token configuration** (in `application.yml`):
```yaml
external-systems:
  sales:
    base-url: http://sales-stub:8081
    token-uri: https://idp.example.com/token
    client-id: unified-viewer-svc
    client-secret: ${SALES_CLIENT_SECRET}
    scope: sales:documents:read
  service:
    base-url: http://service-stub:8082
    token-uri: https://idp.example.com/token
    client-id: unified-viewer-svc
    client-secret: ${SERVICE_CLIENT_SECRET}
    scope: service:documents:read
  # Adding a 3rd system: just add a new block here + a new @Component class
```

---

### JWT Token Lifecycle & Caching

```
┌──────────────────────────────────────────────────────┐
│              JwtTokenCache (in-memory)               │
│                                                      │
│  Map<systemName, CachedToken>                        │
│    CachedToken { token: String, expiresAt: Instant } │
│                                                      │
│  get(system):                                        │
│    if expiresAt > now() + 30s --> return token       │
│    else --> fetch new token, store, return           │
└──────────────────────────────────────────────────────┘
```

The 30-second buffer prevents using a token that expires mid-flight.

---

### Security Summary

| Concern | Solution |
|---------|----------|
| User authentication | JWT (RS256) issued by IdP; validated via JWKS |
| Authorization | `ROLE_OPERATOR` claim enforced by Spring Security |
| Outbound API auth | Client-credentials JWT per external system |
| Token storage (outbound) | In-memory cache with 30 s expiry buffer |
| Transport security | TLS 1.2+ enforced at Nginx; internal traffic via HTTPS |
| Input validation | VIN regex validation before any processing |
| Sensitive data in logs | JWT tokens and user IDs masked/excluded from log output |
| Secret management | Secrets injected via environment variables / Vault |

---

## Data Flow

### Happy Path — Full Aggregation

```
1. User logs in via the Identity Provider; receives a JWT access token.
2. User enters a VIN and submits the search.
3. Browser sends: GET /api/v1/documents?vin=1HGBH41JXMN109186
                  Authorization: Bearer <user-jwt>
4. Nginx forwards the request (JWT header preserved).
5. Spring Security JwtAuthenticationFilter:
   - Fetches JWKS from IdP (cached), validates JWT signature & expiry.
   - Checks ROLE_OPERATOR claim -- rejects with 401 if missing.
   - Populates SecurityContext.
6. DocumentController validates VIN format (regex).
7. DocumentAggregationService checks PostgreSQL for a cached result (TTL not expired).
   |-- CACHE HIT  --> return cached result immediately (steps 8-14 skipped).
   |-- CACHE MISS --> continue to step 8.
8. For each registered ExternalSystemClient (N clients, currently 2), a CompletableFuture task is created:
   |-- Task A: SalesSystemClient.fetchDocuments(vin)
   |          JwtOutboundInterceptor injects service-account JWT
   |          --> GET http://sales-system/sales/documents?vin=...
   |              Authorization: Bearer <service-account-jwt>
   |          --> Returns: [{ documentId, type, date, ... }]
   |-- Task B: ServiceSystemClient.fetchDocuments(vin)
              JwtOutboundInterceptor injects service-account JWT
              --> GET http://service-system/service/documents?vin=...
                  Authorization: Bearer <service-account-jwt>
              --> Returns: [{ documentId, type, date, ... }]
9. CompletableFuture.allOf(allTasks).join() -- waits for all (up to TimeLimiter timeout).
10. Results are merged:
    - Each document is enriched with its sourceSystem label.
    - List is sorted by document date (descending).
11. Merged result is persisted to PostgreSQL with fetched_at = now().
12. DocumentController serializes and returns 200 OK with DocumentAggregateResponse.
13. Browser renders the unified document list with source badges.
```

### Partial Failure Path

```
8. Task A succeeds; Task B times out (circuit breaker opens after threshold).
   Note: If the external system returns 401/403, this is treated as a hard error
   (not retriable) and the circuit breaker records a failed call.
9. allOf completes after TimeLimiter fires for Task B.
10. Service builds response with:
    - documents from Task A (annotated "SALES")
    - partialFailure: true
    - failedSources: ["SERVICE"]
11. 206 Partial Content returned to the client with available documents.
12. UI displays a warning banner: "Service System data temporarily unavailable."
```

### Cache Hit Path

```
5. DocumentAggregationService queries DB:
   SELECT * FROM documents WHERE vin=? AND fetched_at > now() - interval '60s'
   --> Records found --> wrap in DocumentAggregateResponse --> return 200 OK immediately.
```

---

## Technology Choices & Justifications

| Category | Technology | Justification |
|----------|-----------|---------------|
| **Language** | Java 21 (LTS) | Virtual threads (Project Loom) for high concurrency; strong ecosystem; team familiarity; long-term support. |
| **Framework** | Spring Boot 3.3 | De-facto standard for Java microservices; autoconfiguration reduces boilerplate; excellent integration with Micrometer, WebClient, Spring Data, Spring Security. |
| **Security (Inbound)** | Spring Security + OAuth2 Resource Server | Native JWT validation via JWKS; declarative method-level security; minimal boilerplate for RS256 token verification. |
| **Security (Outbound)** | Spring Security OAuth2 Client (`client_credentials`) | Built-in client-credentials grant flow with automatic token refresh; integrates directly with `WebClient` via `ServerOAuth2AuthorizedClientExchangeFilterFunction`. |
| **Identity Provider** | Keycloak (self-hosted) or Auth0 / Okta (SaaS) | Industry-standard OIDC/OAuth2 IdP; issues RS256-signed JWTs; exposes JWKS endpoint; supports client-credentials flow for service accounts. |
| **JWT Library** | `spring-security-oauth2-jose` (Nimbus JOSE) | Ships with Spring Boot; handles JWKS fetching, RS256 signature verification, and claim extraction without extra dependencies. |
| **HTTP Client** | Spring WebClient (Reactor) | Non-blocking I/O; composable async pipelines; first-class reactive support; seamless integration with OAuth2 filter functions for outbound JWT injection. |
| **Parallel Execution** | `CompletableFuture.allOf()` + Virtual Threads | Fan-out pattern; virtual threads (Java 21) remove the need for complex reactive chains while maintaining high throughput; trivially scales to N external clients. |
| **Resilience** | Resilience4j (CircuitBreaker, TimeLimiter, Retry) | Lightweight, annotation-driven; integrates natively with Micrometer for circuit-breaker metrics; no external daemon needed. |
| **Database** | PostgreSQL 16 | ACID compliance; JSONB for flexible document metadata; proven at scale; rich indexing capabilities. |
| **ORM / Persistence** | Spring Data JPA (Hibernate) | Reduces boilerplate CRUD; supports JPQL and native queries; familiar to most Java teams. |
| **Build Tool** | Maven | Conventional for Spring Boot projects; deterministic builds; excellent IDE support. |
| **API Documentation** | SpringDoc OpenAPI (Swagger UI) | Auto-generates OpenAPI 3.x spec from annotations; includes `securitySchemes` for JWT bearer tokens; built-in Swagger UI for interactive testing. |
| **Testing** | JUnit 5, Mockito, WireMock, Testcontainers | Unit tests with Mockito; integration tests with Testcontainers (real PostgreSQL in Docker); WireMock for external API stubs with JWT validation. |
| **Containerization** | Docker + Docker Compose | Local development parity; easy orchestration of service + DB + stubs + Keycloak in one command. |
| **Observability** | Micrometer + Prometheus + Grafana + Loki | Industry-standard OSS stack; Micrometer is natively integrated in Spring Boot Actuator. |
| **Tracing** | Micrometer Tracing (Brave / OpenTelemetry) | Distributed trace IDs propagated across service boundaries and into log lines. |
| **Frontend** | React (TypeScript) or minimal HTML/JS | Thin UI; React for a maintainable component model if the UI grows. |
| **Reverse Proxy** | Nginx | Battle-tested; handles TLS termination, rate limiting, and static file serving. |
| **Secret Management** | Environment variables + HashiCorp Vault (optional) | Client secrets and signing keys are never hardcoded; Vault provides dynamic secrets and rotation. |

---

## API Contract

### Unified Document Viewer Service

#### `GET /api/v1/documents`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `vin` | `string` | Yes | 17-character Vehicle Identification Number |

**Success Response — `200 OK`**

```json
{
  "vin": "1HGBH41JXMN109186",
  "totalCount": 2,
  "partialFailure": false,
  "sources": [
    { "system": "SALES",   "status": "OK", "documentCount": 1 },
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

**Partial Success Response — `206 Partial Content`**

Returned when at least one system succeeds but one or more fail. `sources[i].documentCount` always equals the number of items in `documents[]` where `sourceSystem == sources[i].system`. `totalCount` equals the sum of all `sources[].documentCount`.

```json
{
  "vin": "1HGBH41JXMN109186",
  "totalCount": 2,
  "partialFailure": true,
  "sources": [
    {
      "system": "SALES",
      "status": "OK",
      "documentCount": 2
    },
    {
      "system": "SERVICE",
      "status": "CIRCUIT_OPEN",
      "documentCount": 0,
      "message": "Service System is temporarily unavailable. Retry shortly."
    }
  ],
  "documents": [
    {
      "documentId": "SAL-10042",
      "sourceSystem": "SALES",
      "documentType": "PURCHASE_ORDER",
      "title": "Vehicle Purchase Order",
      "createdAt": "2024-03-15T09:00:00Z",
      "documentUrl": "https://storage.example.com/docs/SAL-10042.pdf?X-Signature=abc&X-Expires=1725360000",
      "metadata": { "amount": 35000, "currency": "USD" }
    },
    {
      "documentId": "SAL-10099",
      "sourceSystem": "SALES",
      "documentType": "WARRANTY",
      "title": "Powertrain Warranty",
      "createdAt": "2024-03-15T09:05:00Z",
      "documentUrl": "https://storage.example.com/docs/SAL-10042.pdf?X-Signature=abc&X-Expires=1725360000",
      "metadata": { "warrantyYears": 5, "mileageLimit": 60000 }
    }
  ]
}
```

**Source Status Values**

| `status` | Meaning |
|----------|---------|
| `OK` | System responded successfully |
| `TIMEOUT` | Call exceeded the configured TimeLimiter (3 s) |
| `CIRCUIT_OPEN` | Circuit breaker is open; call was not attempted |
| `ERROR` | HTTP 5xx or network-level failure |
| `AUTH_ERROR` | HTTP 401/403 — service-account token rejected |

**Required Request Headers**

| Header | Value | Required |
|--------|-------|----------|
| `Authorization` | `Bearer <JWT access token>` | Yes |

**All Responses Summary**

| HTTP Status | Scenario |
|-------------|----------|
| `200 OK` | All external systems responded successfully |
| `206 Partial Content` | At least one system succeeded; one or more failed |
| `400 Bad Request` | VIN is missing or invalid format |
| `401 Unauthorized` | JWT missing, expired, or invalid signature |
| `403 Forbidden` | JWT valid but missing `ROLE_OPERATOR` claim |
| `404 Not Found` | No documents found for the VIN across any system |
| `503 Service Unavailable` | **All** external systems failed simultaneously |

---

### External API Contract (Mocked Systems)

#### Sales System — `GET /sales/documents?vin={vin}`

```json
[
  {
    "documentId": "SAL-10042",
    "type": "PURCHASE_ORDER",
    "title": "Vehicle Purchase Order",
    "createdAt": "2024-03-15T09:00:00Z",
    "documentUrl": "https://storage.example.com/docs/SAL-10042.pdf?X-Signature=abc&X-Expires=1725360000",
    "details": { "amount": 35000, "currency": "USD" }
  },
  {
    "documentId": "SAL-10099",
    "type": "WARRANTY",
    "title": "Powertrain Warranty",
    "createdAt": "2024-03-15T09:05:00Z",
    "documentUrl": "https://storage.example.com/docs/SAL-10042.pdf?X-Signature=abc&X-Expires=1725360000",
    "details": { "warrantyYears": 5, "mileageLimit": 60000 }
  }
]
```

#### Service System — `GET /service/documents?vin={vin}`

```json
[
  {
    "documentId": "SVC-88821",
    "type": "SERVICE_RECORD",
    "title": "60,000 Mile Service",
    "createdAt": "2025-11-20T14:30:00Z",
    "documentUrl": "https://storage.example.com/docs/SAL-10042.pdf?X-Signature=abc&X-Expires=1725360000",
    "details": { "technician": "J. Smith", "mileage": 60142 }
  }
]
```

---

## Data Models

### Database Schema

```sql
-- Stores each fetched document individually for auditing and caching
CREATE TABLE documents (
    id            BIGSERIAL PRIMARY KEY,
    vin           VARCHAR(17)   NOT NULL,
    source_system VARCHAR(20)   NOT NULL,   -- 'SALES' | 'SERVICE'
    document_id   VARCHAR(64)   NOT NULL,
    document_type VARCHAR(64)   NOT NULL,
    title         VARCHAR(255),
    created_at    TIMESTAMPTZ,
    metadata      JSONB,
    fetched_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- Primary query pattern: lookup by VIN within TTL window
CREATE INDEX idx_documents_vin_fetched ON documents (vin, fetched_at DESC);

-- Prevent duplicate document inserts during retries
CREATE UNIQUE INDEX idx_documents_source_docid ON documents (source_system, document_id);
```

### Java Package Structure

```
com.xuanvolab.unifieddocviewer
├── controller
│   └── DocumentController.java
├── service
│   ├── DocumentAggregationService.java
│   ├── client
│   │   ├── ExternalSystemClient.java        (interface — extensibility contract)
│   │   ├── ExternalSystemClientRegistry.java (autowires all implementations)
│   │   ├── SalesSystemClient.java           (implements ExternalSystemClient)
│   │   ├── ServiceSystemClient.java         (implements ExternalSystemClient)
│   │   └── FinanceSystemClient.java         (future — add without modifying core)
│   └── JwtTokenCache.java                   (in-memory service-account token cache)
├── repository
│   └── DocumentRepository.java
├── model
│   ├── Document.java                        (JPA Entity)
│   ├── ExternalDocument.java                (DTO from external APIs)
│   └── DocumentAggregateResponse.java       (API response DTO)
├── security
│   ├── JwtOutboundInterceptor.java          (WebClient ExchangeFilterFunction)
│   └── SecurityConfig.java                  (Spring Security filter chain)
└── config
    ├── WebClientConfig.java
    ├── Resilience4jConfig.java
    ├── ExternalSystemProperties.java        (typed config binding for N systems)
    └── SchedulerConfig.java
```

---

## Observability Strategy

Observability is built on three pillars: **Logs**, **Metrics**, and **Traces**.

### Logs

| Aspect | Approach |
|--------|----------|
| **Framework** | SLF4J + Logback (Spring Boot default) |
| **Format** | Structured JSON logs (Logstash encoder) for machine parsing |
| **Correlation** | `traceId` and `spanId` injected into every log line via MDC |
| **Key Events** | VIN lookup start/end, cache hit/miss, external API call durations, circuit breaker state changes, errors |
| **Shipping** | Log files shipped to **ELK Stack** (Elasticsearch + Logstash + Kibana) or **Grafana Loki** via Promtail |

Example structured log line:
```json
{
  "timestamp": "2026-09-03T10:12:33Z",
  "level": "INFO",
  "traceId": "4bf92f3577b34da6",
  "spanId": "00f067aa0ba902b7",
  "vin": "1HGBH41JXMN109186",
  "event": "AGGREGATION_COMPLETE",
  "salesCount": 3,
  "serviceCount": 2,
  "cacheHit": false,
  "durationMs": 142
}
```

---

### Metrics

| Metric Name | Type | Labels | Purpose |
|-------------|------|--------|---------|
| `document_lookup_total` | Counter | `status` (success/partial/error) | Total lookup volume and outcomes |
| `document_lookup_duration_ms` | Histogram | `cache_hit` | End-to-end latency distribution |
| `external_api_call_duration_ms` | Histogram | `source_system` | Per-system latency percentiles |
| `external_api_errors_total` | Counter | `source_system`, `error_type` | Error rate per external system |
| `circuit_breaker_state` | Gauge | `source_system` | Circuit breaker open/closed/half-open state |
| `cache_hits_total` | Counter | — | Cache effectiveness |
| `cache_misses_total` | Counter | — | Cache miss rate |

**Tooling pipeline**: Spring Boot Actuator → Micrometer → **Prometheus** scrape → **Grafana** dashboards.

Key Grafana dashboards:
1. **Service Health** — error rate, p50/p95/p99 latency, circuit breaker status.
2. **External Systems** — per-system availability, latency percentiles, error counts.
3. **Cache** — hit/miss ratio, eviction rate.

---

### Distributed Tracing

| Aspect | Approach |
|--------|----------|
| **Library** | Micrometer Tracing (wraps Brave or OpenTelemetry SDK) |
| **Propagation** | W3C `traceparent` header propagated to all external API calls |
| **Backend** | **Zipkin** or **Jaeger** (or any OTLP-compatible collector) |
| **Auto-instrumentation** | Spring Boot autoconfigures spans for `WebClient`, Spring MVC, and Spring Data |
| **Custom spans** | Manual spans around the aggregation fan-out for clear trace visualization |

A single VIN lookup trace will show:
```
[GET /api/v1/documents] --+-- [SalesSystemClient.fetch]  (parallel)
                          +-- [ServiceSystemClient.fetch] (parallel)
```

---

### Alerting Rules

| Alert | Condition | Severity |
|-------|-----------|----------|
| High error rate | > 5% 5xx responses in 5 min | Critical |
| High latency | p95 > 2000 ms sustained | Warning |
| Circuit Breaker open | Any CB open for > 30 s | Critical |
| Both systems down | `failedSources` count = 2 for > 1 min | Critical |
| DB connection pool saturation | Pool usage > 90% | Warning |

Alerts are configured in **Prometheus Alertmanager** and routed to Slack or PagerDuty.

---

## Non-Functional Considerations

### Scalability
- The Spring Boot service is **stateless** — horizontal scaling behind a load balancer is trivial.
- PostgreSQL connection pooling via **HikariCP** (default in Spring Boot).
- Database read replicas can be introduced for high-read workloads.
- Virtual threads (Java 21) allow handling thousands of concurrent VIN lookups without thread-pool exhaustion.

### Performance
- Parallel fan-out reduces response time to `max(T_sales, T_service)` instead of `T_sales + T_service`.
- PostgreSQL cache with short TTL (60 s default, configurable) absorbs repeated lookups for the same VIN.
- Configurable connection pool sizes and timeouts to match external system SLAs.

### Reliability
- **Circuit Breaker** per external system — failures in one system do not cascade to the other.
- **TimeLimiter** (e.g., 3 s) ensures the aggregation never hangs indefinitely.
- **Retry** with exponential back-off on transient failures (e.g., 502 Bad Gateway).
- **Partial response** strategy: always return available data; never fail completely due to one system being down.

### Maintainability
- Clean layered architecture: Controller → Service → Client → Repository.
- All external system URLs, timeouts, and TTLs are externalized to `application.yml` / environment variables.
- OpenAPI spec auto-generated for documentation and consumer-driven contract testing.
- `docker-compose.yml` for one-command local environment setup (service + DB + stubs).

### Observability
- Implement **distributed tracing** using **Zipkin** or **Jaeger** to trace requests as they flow through the Unified Service and into the external systems.
- Implement **structured logging** using **ELK Stack** (Elasticsearch + Logstash + Kibana) or **Grafana Loki** via Promtail for machine-parseable logs with correlation IDs.
- Implement **metrics collection** using **Prometheus** and **Grafana** dashboards to monitor service health, external system availability, and cache performance.
- Use **Spring Boot Actuator** with **Micrometer** for auto-instrumented metrics.

### Security
- **Inbound**: JWT (RS256) validated by Spring Security OAuth2 Resource Server; role-based access control (`ROLE_OPERATOR`).
- **Outbound**: Per-system service-account JWT obtained via client-credentials flow; injected by `JwtOutboundInterceptor`; cached with 30 s expiry buffer.
- **Transport**: TLS 1.2+ enforced at Nginx for all external connections.
- **Input**: VIN validated by regex before any processing to prevent injection.
- **Logs**: JWT tokens and `sub` claim values are masked/excluded from structured log output.
- **Secrets**: Client secrets injected via environment variables; HashiCorp Vault recommended for production rotation.
- **Token expiry**: 401 responses from external APIs trigger token cache invalidation and a single retry with a freshly fetched token.

---

## GenAI Usage

Generative AI was used throughout the system design phase as an interactive design partner and architectural reviewer.

### 1. Architectural Exploration & Trade-off Analysis
* **Concurrency Model**: Evaluated Spring WebFlux (reactive event loops) against Spring MVC with Java 21 Virtual Threads (Project Loom). The analysis favored Virtual Threads for its synchronous-style readability and simpler debugging while delivering equivalent high-concurrency I/O performance.
* **Resilience Patterns**: Explored bulkhead isolation, circuit breakers, and rate limiters with Resilience4j to prevent cascade failures when upstream dealership systems experience outages.

### 2. API Contract & Partial Failure Design
* Designed the RESTful API contract for `GET /api/v1/documents?vin=...` including OpenAPI 3 schemas.
* Formulated the partial-failure strategy: utilizing `206 Partial Content` with per-source status breakdowns when one external API fails, rather than failing the entire request.

### 3. Database Caching & Indexing Strategy
* Refined PostgreSQL schema design, adding composite indexes on `(vin, created_at DESC)` and JSONB metadata columns for low-latency cache retrieval and flexible document attributes.

### 4. Security & Observability Validation
* Structured the dual-layer JWT architecture (inbound RBAC with `ROLE_OPERATOR` and outbound client credentials caching).
* Designed observability metrics (counters, latency histograms, circuit breaker gauges) and structured JSON logging with MDC correlation (`traceId`, `spanId`).
