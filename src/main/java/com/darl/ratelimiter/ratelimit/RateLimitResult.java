package com.darl.ratelimiter.ratelimit;

import lombok.Builder;
import lombok.Getter;

/**
 * Result of a single rate limit check.
 * Carries everything needed for the response headers and audit log.
 */
@Getter
@Builder
public class RateLimitResult {

    /** Whether the request is allowed. */
    private final boolean allowed;

    /** The limit that was applied. */
    private final int limit;

    /** Remaining requests in the current window (0 if rejected). */
    private final int remaining;

    /** Epoch second when the current window resets. */
    private final long resetAtEpochSecond;

    /** Current counter value seen in Redis. */
    private final long currentCount;

    /** Algorithm that produced this result. */
    private final String algorithm;

    /** Redis key that was checked. */
    private final String redisKey;

    // ── Convenience factories ──────────────────────────────────

    public static RateLimitResult allowed(int limit, int remaining,
                                          long resetAt, long count,
                                          String algorithm, String key) {
        return RateLimitResult.builder()
                .allowed(true)
                .limit(limit)
                .remaining(remaining)
                .resetAtEpochSecond(resetAt)
                .currentCount(count)
                .algorithm(algorithm)
                .redisKey(key)
                .build();
    }

    public static RateLimitResult rejected(int limit, long resetAt,
                                           long count, String algorithm, String key) {
        return RateLimitResult.builder()
                .allowed(false)
                .limit(limit)
                .remaining(0)
                .resetAtEpochSecond(resetAt)
                .currentCount(count)
                .algorithm(algorithm)
                .redisKey(key)
                .build();
    }
}
