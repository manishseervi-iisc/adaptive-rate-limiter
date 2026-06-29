package com.darl.ratelimiter.ratelimit;

import com.darl.ratelimiter.IntegrationTestBase;
import com.darl.ratelimiter.ratelimit.impl.SlidingWindowRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 12: Integration tests for Sliding Window Log rate limiter against
 * a real Redis instance (Testcontainers).
 */
class SlidingWindowRateLimiterIntegrationTest extends IntegrationTestBase {

    @Autowired
    private SlidingWindowRateLimiter slidingWindowRateLimiter;

    private String clientId;

    @BeforeEach
    void setUp() {
        clientId = "test-sw-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("Requests up to the limit are allowed")
    void requestsUpToLimitAreAllowed() {
        int limit = 5;
        int allowed = 0;

        for (int i = 0; i < limit; i++) {
            RateLimitResult result = slidingWindowRateLimiter.checkAndConsume(clientId, limit, 60, 0);
            if (result.isAllowed()) allowed++;
        }

        assertThat(allowed).isEqualTo(limit);
    }

    @Test
    @DisplayName("Request exceeding limit is rejected")
    void requestExceedingLimitIsRejected() {
        int limit = 3;

        for (int i = 0; i < limit; i++) {
            slidingWindowRateLimiter.checkAndConsume(clientId, limit, 60, 0);
        }

        RateLimitResult overLimit = slidingWindowRateLimiter.checkAndConsume(clientId, limit, 60, 0);
        assertThat(overLimit.isAllowed()).isFalse();
        assertThat(overLimit.getAlgorithm()).isEqualTo("SLIDING_WINDOW");
    }

    @Test
    @DisplayName("Allowed result reports correct remaining count")
    void allowedResultReportsRemainingCount() {
        int limit = 5;

        RateLimitResult first = slidingWindowRateLimiter.checkAndConsume(clientId, limit, 60, 0);
        assertThat(first.isAllowed()).isTrue();
        assertThat(first.getRemaining()).isEqualTo(limit - 1);

        RateLimitResult second = slidingWindowRateLimiter.checkAndConsume(clientId, limit, 60, 0);
        assertThat(second.isAllowed()).isTrue();
        assertThat(second.getRemaining()).isEqualTo(limit - 2);
    }

    @Test
    @DisplayName("Different clients are isolated from each other")
    void clientsAreIsolated() {
        int limit = 2;
        String clientA = clientId + "-A";
        String clientB = clientId + "-B";

        // Exhaust client A
        slidingWindowRateLimiter.checkAndConsume(clientA, limit, 60, 0);
        slidingWindowRateLimiter.checkAndConsume(clientA, limit, 60, 0);
        RateLimitResult rejectedA = slidingWindowRateLimiter.checkAndConsume(clientA, limit, 60, 0);
        assertThat(rejectedA.isAllowed()).isFalse();

        // Client B should be unaffected
        RateLimitResult allowedB = slidingWindowRateLimiter.checkAndConsume(clientB, limit, 60, 0);
        assertThat(allowedB.isAllowed()).isTrue();
    }
}
