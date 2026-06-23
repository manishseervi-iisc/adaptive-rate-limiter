package com.darl.ratelimiter.adaptive;

import com.darl.ratelimiter.audit.AuditLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Day 8: Calls the Python FastAPI /predict endpoint and caches results in Redis.
 *
 * Hot-path contract: {@link #getCachedLimit} reads only from Redis — never blocks
 * on network I/O. Predictions are refreshed asynchronously via {@link #refreshAsync}.
 *
 * Circuit breaker: after {@code circuitBreakerThreshold} consecutive HTTP failures
 * the breaker opens for {@code RECOVERY_MS} ms. Requests during open state skip
 * the ML call and fall back to static config.
 */
@Slf4j
@Service
public class AdaptiveRateLimitService {

    private static final String REDIS_KEY_PREFIX = "adaptive:limit:";
    private static final long RECOVERY_MS = 30_000L;

    private final StringRedisTemplate redis;
    private final AuditLogRepository auditLogRepository;
    private final RestTemplate restTemplate;
    private final String inferenceUrl;
    private final long cacheTtlSeconds;
    private final int circuitBreakerThreshold;

    // Circuit breaker state
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong circuitOpenedAt = new AtomicLong(0L);

    public AdaptiveRateLimitService(
            StringRedisTemplate redis,
            AuditLogRepository auditLogRepository,
            RestTemplateBuilder builder,
            @Value("${darl.adaptive.inference-url:http://localhost:8000}") String inferenceUrl,
            @Value("${darl.adaptive.cache-ttl-seconds:300}") long cacheTtlSeconds,
            @Value("${darl.adaptive.circuit-breaker-threshold:5}") int circuitBreakerThreshold) {
        this.redis = redis;
        this.auditLogRepository = auditLogRepository;
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(500))
                .setReadTimeout(Duration.ofMillis(800))
                .build();
        this.inferenceUrl = inferenceUrl;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.circuitBreakerThreshold = circuitBreakerThreshold;
    }

    /**
     * Non-blocking Redis read. Returns the ML-predicted limit if cached,
     * or empty if cache has expired or was never populated for this client.
     */
    public Optional<Integer> getCachedLimit(String clientId) {
        String value = redis.opsForValue().get(REDIS_KEY_PREFIX + clientId);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            log.warn("Corrupt adaptive cache entry for {}: '{}'", clientId, value);
            return Optional.empty();
        }
    }

    /**
     * Async prediction refresh — runs on the adaptiveExecutor thread pool.
     * Computes rolling features from the audit log, calls the inference
     * service, and writes the result to Redis with TTL.
     *
     * Called from the rate-limit hot path on a cache miss; the calling
     * thread does NOT wait for this to complete.
     */
    @Async("adaptiveExecutor")
    public void refreshAsync(String clientId) {
        if (isCircuitOpen()) {
            log.debug("[Adaptive] circuit open — skipping ML call for {}", clientId);
            return;
        }
        try {
            Map<String, Object> features = buildFeatures(clientId);
            int predicted = callInferenceService(clientId, features);
            writeToCache(clientId, predicted);
            consecutiveFailures.set(0);
            log.debug("[Adaptive] clientId={} predicted={} rps", clientId, predicted);
        } catch (RestClientException e) {
            int failures = consecutiveFailures.incrementAndGet();
            log.warn("[Adaptive] inference call failed for {} (failures={}): {}", clientId, failures, e.getMessage());
            if (failures >= circuitBreakerThreshold) {
                circuitOpenedAt.set(System.currentTimeMillis());
                log.warn("[Adaptive] circuit OPENED after {} consecutive failures", failures);
            }
        } catch (Exception e) {
            log.warn("[Adaptive] unexpected error refreshing limit for {}: {}", clientId, e.getMessage());
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private boolean isCircuitOpen() {
        long openedAt = circuitOpenedAt.get();
        if (openedAt == 0L) return false;
        if (System.currentTimeMillis() - openedAt > RECOVERY_MS) {
            // Allow one probe request (half-open)
            circuitOpenedAt.set(0L);
            consecutiveFailures.set(0);
            log.info("[Adaptive] circuit HALF-OPEN — probing inference service");
            return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildFeatures(String clientId) {
        OffsetDateTime now = OffsetDateTime.now();
        long count1m = auditLogRepository.countByClientIdSince(clientId, now.minusSeconds(60));
        long count5m = auditLogRepository.countByClientIdSince(clientId, now.minusSeconds(300));

        double rps1m = count1m / 60.0;
        double rps5m = count5m / 300.0;
        int hour = LocalDateTime.now().getHour();
        int dow = LocalDateTime.now().getDayOfWeek().getValue();

        return Map.of(
                "rolling_mean_rps_1m", rps1m,
                "rolling_mean_rps_5m", rps5m,
                "rps_p99_1m", rps1m * 1.5,
                "latency_p99_ms", 5.0,
                "hour_of_day", hour,
                "day_of_week", dow
        );
    }

    @SuppressWarnings("unchecked")
    private int callInferenceService(String clientId, Map<String, Object> features) {
        Map<String, Object> body = Map.of("client_id", clientId, "features", features);
        Map<String, Object> response = restTemplate.postForObject(
                inferenceUrl + "/predict", body, Map.class);
        if (response == null || !response.containsKey("predicted_limit")) {
            throw new RestClientException("No predicted_limit in response");
        }
        return ((Number) response.get("predicted_limit")).intValue();
    }

    private void writeToCache(String clientId, int limit) {
        redis.opsForValue().set(
                REDIS_KEY_PREFIX + clientId,
                String.valueOf(limit),
                cacheTtlSeconds,
                TimeUnit.SECONDS
        );
    }
}
