package com.darl.ratelimiter.ratelimit;

import com.darl.ratelimiter.adaptive.AdaptiveRateLimitService;
import com.darl.ratelimiter.audit.AuditService;
import com.darl.ratelimiter.cache.ClientConfigCache;
import com.darl.ratelimiter.config.AppProperties;
import com.darl.ratelimiter.metrics.RateLimitMetricsService;
import com.darl.ratelimiter.model.ClientConfig;
import com.darl.ratelimiter.shadow.ShadowModeService;
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
 * 6. When shadow mode is on, replay check with adaptive limit in isolated key
 *    namespace and record divergence (Day 9)
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
    private final ShadowModeService        shadowModeService;
    private final RateLimitMetricsService  metricsService;

    @Value("${darl.adaptive.enabled:true}")
    private boolean adaptiveEnabled;

    @Value("${darl.shadow-mode.enabled:false}")
    private boolean shadowModeEnabled;

    public RateLimitResult checkLimit(String clientId) {
        ClientConfig config = configCache.get(clientId)
                .orElseGet(() -> defaultConfig(clientId));
        return metricsService.latencyTimer(config.getAlgorithm().name())
                .record(() -> doCheckLimit(clientId, config));
    }

    private RateLimitResult doCheckLimit(String clientId, ClientConfig config) {
        int staticLimit    = config.getRatePerSecond();
        int effectiveLimit = resolveLimit(clientId, config);
        RateLimiter limiter = factory.forAlgorithm(config.getAlgorithm());

        RateLimitResult result = limiter.checkAndConsume(
                clientId,
                effectiveLimit,
                config.getWindowSeconds(),
                effectiveLimit
        );

        metricsService.recordDecision(clientId, result);
        auditService.record(clientId, result);

        // Shadow mode: replay with adaptive limit in isolated key space (Day 9)
        if (shadowModeEnabled) {
            int adaptiveLimit = adaptiveService.getCachedLimit(clientId)
                    .orElse(staticLimit);
            shadowModeService.runShadow(clientId, result, effectiveLimit, adaptiveLimit, config);
        }

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
                    metricsService.recordAdaptiveCacheHit();
                    log.debug("[RateLimitService] using adaptive limit {} for {}", adaptiveLimit, clientId);
                    return adaptiveLimit;
                })
                .orElseGet(() -> {
                    metricsService.recordAdaptiveCacheMiss();
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
