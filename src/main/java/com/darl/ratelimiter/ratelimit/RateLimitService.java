package com.darl.ratelimiter.ratelimit;

import com.darl.ratelimiter.config.AppProperties;
import com.darl.ratelimiter.model.ClientConfig;
import com.darl.ratelimiter.storage.postgres.ClientConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a rate limit check:
 * 1. Load client config from PostgreSQL (with in-memory cache)
 * 2. Select the correct algorithm via {@link RateLimiterFactory}
 * 3. Execute check-and-consume against Redis
 * 4. Return the result (audit logging added in Day 8)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final ClientConfigRepository configRepository;
    private final RateLimiterFactory     factory;
    private final AppProperties          appProperties;

    /**
     * Check and consume one token for the given client.
     * Falls back to default config if no client entry exists in PostgreSQL.
     */
    public RateLimitResult checkLimit(String clientId) {
        ClientConfig config = configRepository
                .findByClientId(clientId)
                .orElseGet(() -> defaultConfig(clientId));

        RateLimiter limiter = factory.forAlgorithm(config.getAlgorithm());

        RateLimitResult result = limiter.checkAndConsume(
                clientId,
                config.getRatePerSecond(),
                config.getWindowSeconds(),
                config.getBurstSize()
        );

        log.debug("rate_limit_check clientId={} algorithm={} allowed={} remaining={}",
                clientId, result.getAlgorithm(), result.isAllowed(), result.getRemaining());

        return result;
    }

    private ClientConfig defaultConfig(String clientId) {
        log.debug("No config found for clientId={}, using defaults", clientId);
        return ClientConfig.builder()
                .clientId(clientId)
                .algorithm(ClientConfig.Algorithm.valueOf(
                        appProperties.getDefaultAlgorithm()))
                .ratePerSecond(appProperties.getDefaultRatePerSecond())
                .burstSize(appProperties.getDefaultBurstSize())
                .windowSeconds(appProperties.getDefaultWindowSeconds())
                .build();
    }
}
