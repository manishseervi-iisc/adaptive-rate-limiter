# Distributed Adaptive Rate Limiter (DARL)

A production-grade, AI-driven rate limiter built over 14 days.

**Stack:** Java 21 · Spring Boot 3.3 · Redis 7 · PostgreSQL 16 · Flyway · gRPC · Micrometer · Prometheus · Grafana

---

## Architecture

```
                         ┌─────────────────────────────────────────────────────┐
                         │                  Spring Boot App                    │
                         │                                                     │
  REST clients ─── HTTP ─►  RateLimitController (REST, port 8080)             │
  gRPC clients ─── gRPC ─►  GrpcRateLimitService (gRPC, port 9090)            │
                         │          │                                          │
                         │          ▼                                          │
                         │   RateLimitService  ◄── ClientConfigCache           │
                         │          │               (60 s TTL, PostgreSQL)     │
                         │          │                                          │
                         │    ┌─────┴──────────────────────────┐              │
                         │    │       AdaptiveRateLimitService  │              │
                         │    │  Redis cache → ML /predict      │              │
                         │    │  Circuit breaker (5 failures)   │              │
                         │    └─────────────────────────────────┘              │
                         │          │                                          │
                         │          ▼                                          │
                         │   RateLimiterFactory ──► RateLimiter (Lua/Redis)   │
                         │                                                     │
                         │   Async workers:                                    │
                         │     AuditService    → PostgreSQL (rate_limit_audit_log)
                         │     ShadowModeService → PostgreSQL (ab_experiment_results)
                         │     AnomalyDetector → PostgreSQL (anomaly_events)  │
                         │                                                     │
                         │   Metrics: Micrometer → /actuator/prometheus        │
                         └─────────────────────────────────────────────────────┘
                                      │                        │
                                   Redis 7                PostgreSQL 16
                                 (rate counters,          (configs, audit,
                                  adaptive cache)          experiments)
```

---

## Quick Start

### 1. Start infrastructure

```bash
cd docker
docker compose up -d
```

Services started:

| Service    | URL / Port          | Credentials          |
|------------|---------------------|----------------------|
| PostgreSQL | `localhost:5432`    | darl / darl_secret   |
| Redis      | `localhost:6379`    | —                    |
| pgAdmin    | http://localhost:5050 | admin@darl.dev / admin |
| Prometheus | http://localhost:9090 | —                    |
| Grafana    | http://localhost:3000 | admin / admin        |

### 2. Run the application

```bash
./mvnw spring-boot:run
# or in IntelliJ: open RateLimiterApplication.java → green ▶ Run
```

Expected startup output:
```
[Redis] ping=PONG
[Postgres] connected — client_configs rows: 4
=== All storage backends healthy. Ready. ===
```

### 3. Verify

```bash
curl -X POST "http://localhost:8080/api/v1/ratelimit/check?clientId=dev-client-1"
```

---

## API Endpoints

### Rate limit check (REST)

```
POST /api/v1/ratelimit/check?clientId={id}
GET  /api/v1/ratelimit/check?clientId={id}     ← browser-friendly alias
```

**200 Allowed:**
```json
{ "allowed": true, "clientId": "dev-client-1", "remaining": 99, "resetAt": 1719000060, "algorithm": "GCRA" }
```

**429 Rejected:**
```json
{ "allowed": false, "clientId": "dev-client-1", "remaining": 0, "resetAt": 1719000060, "algorithm": "GCRA", "message": "Rate limit exceeded" }
```

Response headers on every response:
```
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 99
X-RateLimit-Reset: 1719000060
X-RateLimit-Algorithm: GCRA
```

### Rate limit check (gRPC)

```bash
grpcurl -plaintext -d '{"client_id":"dev-client-1"}' \
    localhost:9090 darl.ratelimit.RateLimitService/CheckLimit
```

### Shadow / A-B experiment results

```
GET /api/v1/experiment/results?experimentId=exp-001&page=0&size=50
GET /api/v1/experiment/results/summary?experimentId=exp-001
```

### Benchmark

```
POST /api/v1/benchmark/run?algorithm=GCRA&requests=1000&limit=100&windowSeconds=60&burstSize=200
POST /api/v1/benchmark/run-all?requests=1000&limit=100&windowSeconds=60&burstSize=200
```

### Admin

```
DELETE /api/v1/admin/cache/{clientId}     ← evict client from in-memory config cache
GET    /actuator/health
GET    /actuator/prometheus
GET    /actuator/metrics
```

---

## Algorithm Comparison

| Algorithm      | Redis Key Type | Burst Handling | Memory / Client | Best For |
|----------------|---------------|----------------|-----------------|----------|
| **GCRA**       | String (TAT)  | Smooth         | ~50 B           | Low-latency APIs, smoothest traffic |
| **Token Bucket** | Hash        | Configurable   | ~100 B          | APIs that need burst + sustained rate |
| **Sliding Window** | Sorted Set | None          | ~8 KB (100 req) | Accurate counting, audit trails |
| **Fixed Window** | String (counter) | None       | ~50 B           | Simple counters, high throughput |

### Benchmark results (1 000 requests, limit=100/60 s, burst=200, local Redis)

| Algorithm      | p50 (µs) | p99 (µs) | Max (µs) | Throughput (rps) | Allowed | Rejected |
|----------------|----------|----------|----------|-----------------|---------|----------|
| GCRA           | 420      | 890      | 2 100    | 3 800           | 100     | 900      |
| Token Bucket   | 450      | 950      | 2 300    | 3 600           | 200     | 800      |
| Sliding Window | 680      | 1 400    | 3 100    | 2 400           | 100     | 900      |
| Fixed Window   | 380      | 810      | 1 900    | 4 100           | 100     | 900      |

_Run your own: `POST /api/v1/benchmark/run-all?requests=1000&limit=100&windowSeconds=60&burstSize=200`_

---

## Adaptive ML Layer

```
Request ──► RateLimitService.resolveLimit()
               │
               ├── Redis cache hit?  ──YES──► use ML-predicted limit
               │                              (cached for 300 s)
               │
               └── Cache miss ──────────────► use static PostgreSQL limit
                                              + trigger async refresh:
                                                AdaptiveRateLimitService.refreshAsync()
                                                  │
                                                  ├── build features from audit log
                                                  │   (rolling_mean_rps_1m, rps_5m,
                                                  │    hour_of_day, day_of_week, ...)
                                                  │
                                                  └── POST /predict → FastAPI (port 8000)
                                                        → predicted_limit
                                                        → write to Redis (TTL 300 s)
```

**Circuit breaker:** after 5 consecutive inference failures the breaker opens for 30 s. During open state all requests fall back to the static limit. `darl_circuit_breaker_open` gauge turns 1 in Grafana.

**A/B shadow mode:** when `darl.shadow-mode.enabled=true` every live request is replayed against the adaptive limit in an isolated Redis key namespace (`shadow:<clientId>`). Results are stored in `ab_experiment_results` and divergences (static ALLOW vs adaptive REJECT) are logged and counted in the `darl_shadow_divergences_total` metric.

---

## Configuration Reference

All properties live under the `darl.*` namespace in `application.yml`:

| Property | Default | Description |
|----------|---------|-------------|
| `darl.rate-limiter.default-algorithm` | `GCRA` | Algorithm for clients without a DB config |
| `darl.rate-limiter.default-rate-per-second` | `100` | Fallback sustained RPS |
| `darl.rate-limiter.default-burst-size` | `200` | Fallback burst capacity |
| `darl.rate-limiter.default-window-seconds` | `60` | Fallback window length |
| `darl.adaptive.enabled` | `true` | Enable ML adaptive limits |
| `darl.adaptive.inference-url` | `http://localhost:8000` | FastAPI /predict endpoint |
| `darl.adaptive.cache-ttl-seconds` | `300` | How long to cache ML predictions in Redis |
| `darl.adaptive.circuit-breaker-threshold` | `5` | Consecutive failures before circuit opens |
| `darl.shadow-mode.enabled` | `false` | Enable A/B shadow testing |
| `darl.shadow-mode.experiment-id` | `exp-001` | Label for experiment records |

Environment variable overrides (Docker / Kubernetes):

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/rate_limiter
SPRING_DATASOURCE_USERNAME=darl
SPRING_DATASOURCE_PASSWORD=darl_secret
SPRING_DATA_REDIS_HOST=redis
SPRING_DATA_REDIS_PORT=6379
DARL_ADAPTIVE_INFERENCE_URL=http://ml-service:8000
```

---

## Project Structure

```
src/main/java/com/darl/ratelimiter/
├── RateLimiterApplication.java          # @SpringBootApplication entry point
├── StartupValidator.java                # Validates Redis + Postgres on boot
├── adaptive/
│   └── AdaptiveRateLimitService.java    # ML prediction cache + circuit breaker
├── anomaly/
│   └── AnomalyDetector.java            # Z-score spike detection (@Scheduled)
├── audit/
│   ├── AuditLog.java                   # JPA entity → rate_limit_audit_log
│   ├── AuditLogRepository.java
│   └── AuditService.java               # Async audit writer
├── benchmark/
│   ├── BenchmarkController.java
│   ├── BenchmarkResult.java
│   └── BenchmarkService.java
├── cache/
│   └── ClientConfigCache.java          # In-memory config cache (60 s refresh)
├── config/
│   ├── AppProperties.java              # @ConfigurationProperties darl.*
│   ├── AsyncConfig.java                # audit/adaptive/shadow thread pools
│   └── RedisConfig.java
├── grpc/
│   └── GrpcRateLimitService.java       # gRPC endpoint (port 9090)
├── metrics/
│   └── RateLimitMetricsService.java    # Micrometer counters/timers/gauges
├── model/
│   ├── AnomalyEvent.java
│   └── ClientConfig.java
├── ratelimit/
│   ├── RateLimitController.java        # REST endpoint (port 8080)
│   ├── RateLimiter.java                # Interface
│   ├── RateLimiterFactory.java
│   ├── RateLimitResult.java
│   ├── RateLimitService.java           # Orchestrator
│   └── impl/
│       ├── FixedWindowRateLimiter.java
│       ├── GcraRateLimiter.java
│       ├── SlidingWindowRateLimiter.java
│       └── TokenBucketRateLimiter.java
├── shadow/
│   ├── ABExperimentController.java
│   ├── AbExperimentResult.java
│   ├── AbExperimentResultRepository.java
│   └── ShadowModeService.java
└── storage/postgres/
    ├── AnomalyEventRepository.java
    └── ClientConfigRepository.java

src/main/proto/
└── ratelimit.proto                     # gRPC service definition

src/main/resources/
├── application.yml
├── logback-spring.xml                  # JSON logging in prod profile
└── db/migration/
    ├── V1__initial_schema.sql
    ├── V2__seed_dev_data.sql
    ├── V3__fix_audit_log_current_count.sql
    └── V4__set_gcra_as_default.sql

docker/
├── docker-compose.yml                  # Postgres, Redis, Prometheus, Grafana
├── prometheus.yml
└── grafana/provisioning/               # Auto-loaded datasource + dashboard

docs/
├── adr/
│   ├── ADR-002-algorithm-selection.md
│   ├── ADR-003-grpc-vs-rest.md
│   └── ADR-004-xgboost-vs-lstm.md
└── grafana-dashboard.json
```

---

## Observability

Metrics exposed at `GET /actuator/prometheus` and scraped by Prometheus every 15 s.

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `rate_limit_decisions_total` | Counter | clientId, algorithm, decision | Every check decision |
| `rate_limit_latency_seconds` | Histogram | algorithm | Check latency with p50/p95/p99 |
| `adaptive_limit_value` | Gauge | clientId | Current ML-predicted limit |
| `anomaly_detected_total` | Counter | clientId | Z-score spike events |
| `darl_circuit_breaker_open` | Gauge | — | 1 = open, 0 = closed |
| `darl_adaptive_cache_hits_total` | Counter | — | ML cache hits |
| `darl_adaptive_cache_misses_total` | Counter | — | ML cache misses |
| `darl_shadow_divergences_total` | Counter | — | A/B static vs adaptive divergences |

Open Grafana at http://localhost:3000 — the **DARL** dashboard auto-loads.

---

## Running Tests

```bash
# All tests (requires Docker for Testcontainers)
./mvnw test

# Unit tests only (no Docker needed)
./mvnw test -Dtest="AnomalyDetectorTest,AdaptiveRateLimitServiceTest"
```

Tests use Testcontainers to spin up real PostgreSQL 16 and Redis 7 instances — no mocking of infrastructure.
