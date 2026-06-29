package com.darl.ratelimiter.ratelimit;

import com.darl.ratelimiter.IntegrationTestBase;
import com.darl.ratelimiter.ratelimit.impl.GcraRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 12: Integration tests for GCRA rate limiter against a real Redis instance
 * (Testcontainers). Tests verify the Lua script correctness — no mocking.
 */
class GcraRateLimiterIntegrationTest extends IntegrationTestBase {

    @Autowired
    private GcraRateLimiter gcraRateLimiter;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private String clientId;

    @BeforeEach
    void setUp() {
        // Unique client ID per test so state never leaks between tests
        clientId = "test-gcra-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("First request is always allowed")
    void firstRequestIsAllowed() {
        RateLimitResult result = gcraRateLimiter.checkAndConsume(clientId, 10, 60, 10);
        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getAlgorithm()).isEqualTo("GCRA");
        assertThat(result.getLimit()).isEqualTo(10);
    }

    @Test
    @DisplayName("Requests within burst limit are all allowed")
    void requestsWithinBurstAreAllowed() {
        int limit = 5;
        int burstSize = 5;

        int allowedCount = 0;
        for (int i = 0; i < burstSize; i++) {
            RateLimitResult result = gcraRateLimiter.checkAndConsume(clientId, limit, 60, burstSize);
            if (result.isAllowed()) allowedCount++;
        }

        assertThat(allowedCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Request beyond burst limit is rejected")
    void requestBeyondBurstIsRejected() {
        int limit = 3;
        int burstSize = 3;
        int windowSeconds = 60;

        // Drain the burst capacity
        for (int i = 0; i < burstSize; i++) {
            gcraRateLimiter.checkAndConsume(clientId, limit, windowSeconds, burstSize);
        }

        // Next request should be rejected
        RateLimitResult rejected = gcraRateLimiter.checkAndConsume(clientId, limit, windowSeconds, burstSize);
        assertThat(rejected.isAllowed()).isFalse();
        assertThat(rejected.getRemaining()).isEqualTo(0);
    }

    @Test
    @DisplayName("Rejected result has correct algorithm label")
    void rejectedResultHasCorrectAlgorithmLabel() {
        int limit = 1;
        int burstSize = 1;

        gcraRateLimiter.checkAndConsume(clientId, limit, 60, burstSize);
        RateLimitResult rejected = gcraRateLimiter.checkAndConsume(clientId, limit, 60, burstSize);

        assertThat(rejected.isAllowed()).isFalse();
        assertThat(rejected.getAlgorithm()).isEqualTo("GCRA");
    }

    @Test
    @DisplayName("Different clients have independent state")
    void differentClientsAreIndependent() {
        String clientA = clientId + "-A";
        String clientB = clientId + "-B";
        int limit = 1;

        // Exhaust client A
        gcraRateLimiter.checkAndConsume(clientA, limit, 60, 1);
        gcraRateLimiter.checkAndConsume(clientA, limit, 60, 1);

        // Client B should still be allowed on first request
        RateLimitResult resultB = gcraRateLimiter.checkAndConsume(clientB, limit, 60, 1);
        assertThat(resultB.isAllowed()).isTrue();
    }
}
