package com.darl.ratelimiter.ratelimit.impl;

import com.darl.ratelimiter.ratelimit.RateLimitResult;
import com.darl.ratelimiter.ratelimit.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Sliding Window Log algorithm.
 *
 * How it works:
 * - Each request is stored as a scored entry in a Redis Sorted Set
 *   where the score = current timestamp in milliseconds
 * - On each check, entries older than (now - windowMs) are removed
 * - If the remaining count < limit, the request is allowed and logged
 *
 * Redis key: rl:sliding:{clientId}
 *
 * AI concept used here: this is the algorithm the ML model will learn
 * to predict optimal limits for — it produces the cleanest time-series
 * signal because every request is individually timestamped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlidingWindowRateLimiter implements RateLimiter {

    private final StringRedisTemplate redisTemplate;

    /**
     * Atomic Lua script — executes as a single Redis command, no race conditions.
     *
     * KEYS[1] = the sorted set key
     * ARGV[1] = current timestamp (ms)
     * ARGV[2] = window start timestamp (ms) = now - windowMs
     * ARGV[3] = limit (max requests)
     * ARGV[4] = TTL in seconds (window length + buffer)
     *
     * Returns: { current_count_after_add, count_in_window }
     * If count > limit, the just-added entry is removed (rejected).
     */
    private static final String SLIDING_WINDOW_SCRIPT = """
            local key        = KEYS[1]
            local now        = tonumber(ARGV[1])
            local windowStart = tonumber(ARGV[2])
            local limit      = tonumber(ARGV[3])
            local ttl        = tonumber(ARGV[4])
            
            -- Remove entries outside the window
            redis.call('ZREMRANGEBYSCORE', key, '-inf', windowStart)
            
            -- Count requests currently in window
            local count = redis.call('ZCARD', key)
            
            if count < limit then
                -- Allow: add this request with score = now
                redis.call('ZADD', key, now, now .. '-' .. math.random(1000000))
                redis.call('EXPIRE', key, ttl)
                return {1, count + 1}   -- allowed=1, new_count
            else
                -- Reject
                return {0, count}       -- allowed=0, current_count
            end
            """;

    private final DefaultRedisScript<List> script = new DefaultRedisScript<>(
            SLIDING_WINDOW_SCRIPT, List.class
    );

    @Override
    public RateLimitResult checkAndConsume(String clientId, int limit,
                                           int windowSeconds, int burstSize) {
        String key = "rl:sliding:" + clientId;
        long nowMs = Instant.now().toEpochMilli();
        long windowStartMs = nowMs - (windowSeconds * 1000L);
        long resetAt = Instant.now().getEpochSecond() + windowSeconds;

        List result = redisTemplate.execute(
                script,
                List.of(key),
                String.valueOf(nowMs),
                String.valueOf(windowStartMs),
                String.valueOf(limit),
                String.valueOf(windowSeconds + 1)   // TTL with 1s buffer
        );

        boolean allowed = ((Number) result.get(0)).intValue() == 1;
        long count = ((Number) result.get(1)).longValue();

        log.debug("[SlidingWindow] clientId={} allowed={} count={}/{}", clientId, allowed, count, limit);

        if (allowed) {
            return RateLimitResult.allowed(limit, (int) (limit - count), resetAt, count, algorithmName(), key);
        } else {
            return RateLimitResult.rejected(limit, resetAt, count, algorithmName(), key);
        }
    }

    @Override
    public String algorithmName() {
        return "SLIDING_WINDOW";
    }
}
