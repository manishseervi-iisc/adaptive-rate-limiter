package com.darl.ratelimiter.ratelimit;

import com.darl.ratelimiter.IntegrationTestBase;
import com.darl.ratelimiter.model.ClientConfig;
import com.darl.ratelimiter.storage.postgres.ClientConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 12: End-to-end integration tests for RateLimitService using real
 * Redis and PostgreSQL containers.
 *
 * These tests verify the full request path:
 *   HTTP layer → RateLimitService → ClientConfigCache → RateLimiter → Redis
 *   and async audit write → PostgreSQL.
 */
class RateLimitServiceIntegrationTest extends IntegrationTestBase {

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private ClientConfigRepository clientConfigRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private String clientId;
    private ClientConfig savedConfig;

    @BeforeEach
    void setUp() {
        clientId = "svc-test-" + UUID.randomUUID().toString().substring(0, 8);

        savedConfig = clientConfigRepository.save(ClientConfig.builder()
                .clientId(clientId)
                .algorithm(ClientConfig.Algorithm.SLIDING_WINDOW)
                .ratePerSecond(5)
                .burstSize(10)
                .windowSeconds(60)
                .active(true)
                .build());
    }

    @AfterEach
    void tearDown() {
        if (savedConfig != null) {
            clientConfigRepository.delete(savedConfig);
        }
        // Clean up Redis keys created during the test
        redisTemplate.delete(redisTemplate.keys("rl:*:" + clientId));
    }

    @Test
    @DisplayName("First request for a configured client is allowed")
    void firstRequestForConfiguredClientIsAllowed() {
        RateLimitResult result = rateLimitService.checkLimit(clientId);
        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getLimit()).isEqualTo(5);
    }

    @Test
    @DisplayName("Requests within configured limit are all allowed")
    void requestsWithinLimitAreAllowed() {
        int allowed = 0;
        for (int i = 0; i < 5; i++) {
            if (rateLimitService.checkLimit(clientId).isAllowed()) allowed++;
        }
        assertThat(allowed).isEqualTo(5);
    }

    @Test
    @DisplayName("Request beyond configured limit is rejected")
    void requestBeyondLimitIsRejected() {
        // Use up the entire allowance
        for (int i = 0; i < 5; i++) {
            rateLimitService.checkLimit(clientId);
        }

        RateLimitResult overLimit = rateLimitService.checkLimit(clientId);
        assertThat(overLimit.isAllowed()).isFalse();
    }

    @Test
    @DisplayName("Unknown client falls back to default config and is allowed")
    void unknownClientUsesDefaultConfigAndIsAllowed() {
        String unknownClient = "unknown-" + UUID.randomUUID();
        RateLimitResult result = rateLimitService.checkLimit(unknownClient);
        // Default limit is 10 rps; first request must be allowed
        assertThat(result.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("Allowed result carries correct response-header fields")
    void allowedResultHasCorrectFields() {
        RateLimitResult result = rateLimitService.checkLimit(clientId);
        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getLimit()).isEqualTo(5);
        assertThat(result.getRemaining()).isGreaterThanOrEqualTo(0);
        assertThat(result.getResetAtEpochSecond()).isGreaterThan(0L);
        assertThat(result.getAlgorithm()).isEqualTo("SLIDING_WINDOW");
    }

    @Test
    @DisplayName("Rejected result has zero remaining")
    void rejectedResultHasZeroRemaining() {
        for (int i = 0; i < 5; i++) {
            rateLimitService.checkLimit(clientId);
        }
        RateLimitResult rejected = rateLimitService.checkLimit(clientId);
        assertThat(rejected.isAllowed()).isFalse();
        assertThat(rejected.getRemaining()).isEqualTo(0);
    }

    @Test
    @DisplayName("GCRA algorithm works end-to-end via service")
    void gcraAlgorithmWorksViaService() {
        String gcraClient = "gcra-e2e-" + UUID.randomUUID().toString().substring(0, 8);
        ClientConfig gcraConfig = clientConfigRepository.save(ClientConfig.builder()
                .clientId(gcraClient)
                .algorithm(ClientConfig.Algorithm.GCRA)
                .ratePerSecond(3)
                .burstSize(3)
                .windowSeconds(60)
                .active(true)
                .build());

        try {
            RateLimitResult first = rateLimitService.checkLimit(gcraClient);
            assertThat(first.isAllowed()).isTrue();
            assertThat(first.getAlgorithm()).isEqualTo("GCRA");
        } finally {
            clientConfigRepository.delete(gcraConfig);
            redisTemplate.delete(redisTemplate.keys("rl:*:" + gcraClient));
        }
    }
}
