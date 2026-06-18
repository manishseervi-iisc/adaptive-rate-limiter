package com.darl.ratelimiter.ratelimit;

import com.darl.ratelimiter.audit.AuditService;
import com.darl.ratelimiter.cache.ClientConfigCache;
import com.darl.ratelimiter.config.AppProperties;
import com.darl.ratelimiter.model.ClientConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a rate limit check:
 * 1. Load client config from in-memory cache (falls back to PostgreSQL on miss)
 * 2. Select the correct algorithm via RateLimiterFactory
 * 3. Execute check-and-consume against Redis (sub-millisecond)
 * 4. Async-write audit record to PostgreSQL (never blocks request)
 *
 * Latency profile after Day 5:
 * - Cache hit:  ~2ms  (Redis round trip only)
 * - Cache miss: ~5ms  (Redis + PostgreSQL on first request for new client)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final ClientConfigCache  configCache;
    private final RateLimiterFactory factory;
    private final AppProperties      appProperties;
    private final AuditService       auditService;

    public RateLimitResult checkLimit(String clientId) {
        // Cache hit = zero DB cost. Cache miss = one PostgreSQL query then cached.
        ClientConfig config = configCache.get(clientId)
                .orElseGet(() -> defaultConfig(clientId));

        RateLimiter limiter = factory.forAlgorithm(config.getAlgorithm());

        RateLimitResult result = limiter.checkAndConsume(
                clientId,
                config.getRatePerSecond(),
                config.getWindowSeconds(),
                config.getBurstSize()
        );

        // Fire-and-forget async write — never blocks this thread
        auditService.record(clientId, result);

        log.debug("rate_limit_check clientId={} algorithm={} allowed={} remaining={}",
                clientId, result.getAlgorithm(), result.isAllowed(), result.getRemaining());

        return result;
    }

    private ClientConfig defaultConfig(String clientId) {
        log.debug("[RateLimitService] no config found for clientId={}, using defaults", clientId);
        return ClientConfig.builder()
                .clientId(clientId)
                .algorithm(ClientConfig.Algorithm.valueOf(appProperties.getDefaultAlgorithm()))
                .ratePerSecond(appProperties.getDefaultRatePerSecond())
                .burstSize(appProperties.getDefaultBurstSize())
                .windowSeconds(appProperties.getDefaultWindowSeconds())
                .build();
    }
}
