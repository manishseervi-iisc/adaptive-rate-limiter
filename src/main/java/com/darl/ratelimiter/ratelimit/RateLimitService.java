package com.darl.ratelimiter.ratelimit;

import com.darl.ratelimiter.adaptive.AdaptiveRateLimitService;
import com.darl.ratelimiter.audit.AuditService;
import com.darl.ratelimiter.cache.ClientConfigCache;
import com.darl.ratelimiter.config.AppProperties;
import com.darl.ratelimiter.model.ClientConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a rate limit check:
 * 1. Load client config from in-memory cache (falls back to default)
 * 2. Override limit with ML-predicted value from Redis if available (Day 8)
 * 3. Select algorithm via RateLimiterFactory and execute Lua script on Redis
 * 4. Async-write audit record to PostgreSQL (never blocks request)
 * 5. On adaptive cache miss, trigger background prediction refresh
 *
 * Latency profile:
 * - Adaptive cache hit:  ~2ms  (two Redis reads: adaptive cache + rate check)
 * - Adaptive cache miss: ~2ms  (falls back to static, triggers async refresh)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final ClientConfigCache        configCache;
    private final RateLimiterFactory       factory;
    private final AppProperties            appProperties;
    private final AuditService             auditService;
    private final AdaptiveRateLimitService adaptiveService;

    @Value("${darl.adaptive.enabled:true}")
    private boolean adaptiveEnabled;

    public RateLimitResult checkLimit(String clientId) {
        ClientConfig config = configCache.get(clientId)
                .orElseGet(() -> defaultConfig(clientId));

        int effectiveLimit = resolveLimit(clientId, config);
        RateLimiter limiter = factory.forAlgorithm(config.getAlgorithm());

        RateLimitResult result = limiter.checkAndConsume(
                clientId,
                effectiveLimit,
                config.getWindowSeconds(),
                effectiveLimit
        );

        auditService.record(clientId, result);

        log.debug("rate_limit_check clientId={} algorithm={} limit={} allowed={} remaining={}",
                clientId, result.getAlgorithm(), effectiveLimit, result.isAllowed(), result.getRemaining());

        return result;
    }

    /**
     * Priority order:
     *   1. ML adaptive limit from Redis cache  (if adaptive enabled)
     *   2. Static limit from PostgreSQL config
     * On cache miss, enqueue an async refresh so next request gets ML limit.
     */
    private int resolveLimit(String clientId, ClientConfig config) {
        if (!adaptiveEnabled) {
            return config.getRatePerSecond();
        }
        return adaptiveService.getCachedLimit(clientId)
                .map(adaptiveLimit -> {
                    log.debug("[RateLimitService] using adaptive limit {} for {}", adaptiveLimit, clientId);
                    return adaptiveLimit;
                })
                .orElseGet(() -> {
                    adaptiveService.refreshAsync(clientId);
                    return config.getRatePerSecond();
                });
    }

    private ClientConfig defaultConfig(String clientId) {
        return ClientConfig.builder()
                .clientId(clientId)
                .algorithm(ClientConfig.Algorithm.valueOf(appProperties.getDefaultAlgorithm()))
                .ratePerSecond(appProperties.getDefaultRatePerSecond())
                .burstSize(appProperties.getDefaultBurstSize())
                .windowSeconds(appProperties.getDefaultWindowSeconds())
                .build();
    }
}
