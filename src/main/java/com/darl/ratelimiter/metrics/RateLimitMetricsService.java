package com.darl.ratelimiter.metrics;

import com.darl.ratelimiter.ratelimit.RateLimitResult;
import io.micrometer.core.instrument.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Day 13: Central Micrometer metrics registry.
 *
 * Metrics exposed at /actuator/prometheus:
 *
 *   rate_limit_decisions_total{clientId, algorithm, decision}
 *   rate_limit_latency_seconds{algorithm}        (Timer)
 *   adaptive_limit_value{clientId}               (Gauge — current ML-predicted limit)
 *   anomaly_detected_total{clientId}             (Counter)
 *
 * Additional internal metrics:
 *   darl_adaptive_cache_hits_total / darl_adaptive_cache_misses_total
 *   darl_circuit_breaker_open                    (Gauge 0/1)
 *   darl_shadow_divergences_total
 */
@Service
@RequiredArgsConstructor
public class RateLimitMetricsService {

    private final MeterRegistry registry;

    // Adaptive limit values per client for the gauge — guarded by ConcurrentHashMap
    private final ConcurrentHashMap<String, AtomicLong> adaptiveLimitGauges = new ConcurrentHashMap<>();

    // Circuit-breaker state
    private final AtomicInteger circuitBreakerOpenFlag = new AtomicInteger(0);

    @PostConstruct
    void registerStaticGauges() {
        Gauge.builder("darl.circuit_breaker.open", circuitBreakerOpenFlag, AtomicInteger::get)
                .description("1 if the adaptive inference circuit breaker is open")
                .register(registry);
    }

    // ── rate_limit_decisions_total ────────────────────────────────────────────

    public void recordDecision(String clientId, RateLimitResult result) {
        Counter.builder("rate_limit_decisions")
                .tag("clientId",  clientId)
                .tag("algorithm", result.getAlgorithm())
                .tag("decision",  result.isAllowed() ? "allowed" : "rejected")
                .description("Total rate-limit check decisions")
                .register(registry)
                .increment();
    }

    // ── rate_limit_latency_seconds ────────────────────────────────────────────

    /** Returns a per-algorithm Timer. The algorithm tag is set at record time. */
    public Timer latencyTimer(String algorithm) {
        return Timer.builder("rate_limit_latency")
                .tag("algorithm", algorithm)
                .description("Rate-limit check latency per algorithm")
                .register(registry);
    }

    // ── adaptive_limit_value (Gauge per clientId) ─────────────────────────────

    /**
     * Update the gauge for this client's current ML-predicted limit.
     * The gauge is registered lazily on first call and reused thereafter.
     */
    public void setAdaptiveLimit(String clientId, int limit) {
        AtomicLong holder = adaptiveLimitGauges.computeIfAbsent(clientId, id -> {
            AtomicLong h = new AtomicLong(limit);
            Gauge.builder("adaptive_limit_value", h, AtomicLong::get)
                    .tag("clientId", id)
                    .description("Current ML-predicted rate limit per client")
                    .register(registry);
            return h;
        });
        holder.set(limit);
    }

    // ── anomaly_detected_total ────────────────────────────────────────────────

    public void recordAnomalyEvent(String clientId) {
        Counter.builder("anomaly_detected")
                .tag("clientId", clientId)
                .description("Z-score traffic spike anomalies detected")
                .register(registry)
                .increment();
    }

    // ── Internal metrics (cache, circuit breaker, shadow) ────────────────────

    public void recordAdaptiveCacheHit() {
        Counter.builder("darl.adaptive.cache.hits")
                .description("Adaptive ML limit cache hits")
                .register(registry)
                .increment();
    }

    public void recordAdaptiveCacheMiss() {
        Counter.builder("darl.adaptive.cache.misses")
                .description("Adaptive ML limit cache misses — triggers async refresh")
                .register(registry)
                .increment();
    }

    public void setCircuitBreakerOpen(boolean open) {
        circuitBreakerOpenFlag.set(open ? 1 : 0);
    }

    public void recordShadowDivergence() {
        Counter.builder("darl.shadow.divergences")
                .description("A/B shadow decisions where static != adaptive")
                .register(registry)
                .increment();
    }
}
