package com.darl.ratelimiter.ratelimit;

/**
 * Common contract for all rate limiting algorithms.
 *
 * Every implementation must be:
 * - Atomic: check-and-consume in a single Redis operation (Lua script)
 * - Stateless: no instance state — all state lives in Redis
 * - Thread-safe: safe to call from concurrent request threads
 */
public interface RateLimiter {

    /**
     * Check whether the client is within their rate limit and consume one token.
     *
     * @param clientId  identifies the caller (API key, user ID, IP, service name)
     * @param limit     maximum allowed requests in the configured window
     * @param windowSeconds  window length in seconds (used by sliding/fixed window)
     * @param burstSize max burst capacity (used by token bucket and GCRA)
     * @return result containing the decision and current counter metadata
     */
    RateLimitResult checkAndConsume(String clientId, int limit, int windowSeconds, int burstSize);

    /**
     * The algorithm name — used for logging and audit records.
     */
    String algorithmName();
}
