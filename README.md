# distributed_adaptive_rate_limiter

Distributed rate limiter with AI-driven adaptive limits.
**Stack: Java 21 · Spring Boot 3.3 · Redis Cluster · PostgreSQL 16 · Flyway**

## Prerequisites

- JDK 21 (IntelliJ will prompt if missing — use the bundled JDK downloader)
- Docker & Docker Compose
- Maven 3.9+ (or use the `./mvnw` wrapper included)

## Quickstart (Day 1)

### 1. Start infrastructure

```bash
cd docker
docker compose up -d
```

Wait ~5 seconds, then bootstrap the Redis cluster:

```bash
# Mac/Linux
chmod +x scripts/bootstrap_redis_cluster.sh && ./scripts/bootstrap_redis_cluster.sh

# Windows (PowerShell)
docker exec darl-redis-1 redis-cli --cluster create `
  redis-1:6379 redis-2:6380 redis-3:6381 `
  --cluster-replicas 0 --cluster-yes
```

### 2. Open in IntelliJ IDEA

1. **File → Open** → select this folder
2. IntelliJ detects `pom.xml` → click **Load Maven Project**
3. Wait for indexing to finish (~1 min first time)
4. Open `RateLimiterApplication.java` → click the green **▶ Run** button

### 3. Expected startup output

```
[Redis] cluster_state=OK slots_assigned=16384 known_nodes=3
[Redis] shard localhost:6379 -> OK
[Redis] shard localhost:6380 -> OK
[Redis] shard localhost:6381 -> OK
[Postgres] connected — client_configs rows: 4
[Postgres] loaded config: clientId=dev-client-1 algorithm=SLIDING_WINDOW rps=100
...
=== All storage backends healthy. Ready. ===
```

### 4. Verify health endpoint

```bash
curl http://localhost:8080/actuator/health | jq .
```

Expected:
```json
{
  "status": "UP",
  "components": {
    "db":           { "status": "UP" },
    "redisCluster": { "status": "UP", "details": { "cluster_state": "ok" } }
  }
}
```

### 5. pgAdmin (optional)

Open http://localhost:5050 → `admin@darl.dev` / `admin`
Add server → host: `darl-postgres`, port: `5432`, user: `darl`, password: `darl_secret`

## Project structure

```
src/main/java/com/darl/ratelimiter/
├── RateLimiterApplication.java       # @SpringBootApplication entrypoint
├── StartupValidator.java             # Validates Redis + Postgres on boot
├── config/
│   ├── AppProperties.java            # @ConfigurationProperties (darl.rate-limiter.*)
│   └── RedisConfig.java              # Lettuce cluster + RedisTemplate beans
├── health/
│   └── RedisClusterHealthIndicator.java   # /actuator/health → redisCluster
├── model/
│   └── ClientConfig.java            # JPA entity → client_configs table
└── storage/
    └── postgres/
        └── ClientConfigRepository.java    # Spring Data JPA repository

src/main/resources/
├── application.yml                   # All config (Redis, Postgres, Flyway, Actuator)
└── db/migration/
    ├── V1__initial_schema.sql        # 5 tables + indexes + trigger
    └── V2__seed_dev_data.sql         # 4 dev client configs
```

## Environment variable overrides

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/rate_limiter` | PostgreSQL URL |
| `SPRING_DATASOURCE_USERNAME` | `darl` | |
| `SPRING_DATASOURCE_PASSWORD` | `darl_secret` | |
| `SPRING_DATA_REDIS_CLUSTER_NODES` | `localhost:6379,...` | Comma-separated |
| `DARL_RATE_LIMITER_DEFAULT_ALGORITHM` | `SLIDING_WINDOW` | |

## Day 3–4 next

Implementing the four rate limiting algorithms behind a common `RateLimiter` interface:
- `SlidingWindowRateLimiter` — Redis Sorted Set + Lua script
- `TokenBucketRateLimiter` — Redis String counter + TTL
- `FixedWindowRateLimiter` — Redis INCR + EXPIRE
- `GcraRateLimiter` — Generic Cell Rate Algorithm
