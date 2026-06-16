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
 * Fixed Window Counter algorithm.
 *
 * How it works:
 * - Time is divided into fixed windows (e.g. 60s buckets)
 * - Each window has its own Redis counter key
 * - Counter resets automatically when the key expires
 *
 * Redis key: rl:fixed:{clientId}:{window_bucket}
 * where window_bucket = floor(now / windowSeconds)
 *
 * Trade-off: simple and fast, but allows 2x burst at window boundaries.
 * The ML model will learn to detect this boundary-burst pattern and
 * recommend switching clients to sliding window when it occurs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FixedWindowRateLimiter implements RateLimiter {

    private final StringRedisTemplate redisTemplate;

    /**
     * Lua script — atomically increments and checks in one round trip.
     *
     * KEYS[1] = counter key (includes window bucket in name)
     * ARGV[1] = limit
     * ARGV[2] = TTL (window length in seconds)
     *
     * Returns: { allowed (1/0), current_count }
     */
    private static final String FIXED_WINDOW_SCRIPT = """
            local key   = KEYS[1]
            local limit = tonumber(ARGV[1])
            local ttl   = tonumber(ARGV[2])
            
            local count = redis.call('INCR', key)
            
            if count == 1 then
                -- First request in this window — set expiry
                redis.call('EXPIRE', key, ttl)
            end
            
            if count <= limit then
                return {1, count}
            else
                return {0, count}
            end
            """;

    private final DefaultRedisScript<List> script = new DefaultRedisScript<>(
            FIXED_WINDOW_SCRIPT, List.class
    );

    @Override
    public RateLimitResult checkAndConsume(String clientId, int limit,
                                           int windowSeconds, int burstSize) {
        long nowSecs = Instant.now().getEpochSecond();
        long windowBucket = nowSecs / windowSeconds;
        long windowStart = windowBucket * windowSeconds;
        long resetAt = windowStart + windowSeconds;

        String key = "rl:fixed:" + clientId + ":" + windowBucket;

        List result = redisTemplate.execute(
                script,
                List.of(key),
                String.valueOf(limit),
                String.valueOf(windowSeconds)
        );

        boolean allowed = ((Number) result.get(0)).intValue() == 1;
        long count = ((Number) result.get(1)).longValue();

        log.debug("[FixedWindow] clientId={} allowed={} count={}/{} bucket={}",
                clientId, allowed, count, limit, windowBucket);

        if (allowed) {
            return RateLimitResult.allowed(limit, (int) (limit - count), resetAt, count, algorithmName(), key);
        } else {
            return RateLimitResult.rejected(limit, resetAt, count, algorithmName(), key);
        }
    }

    @Override
    public String algorithmName() {
        return "FIXED_WINDOW";
    }
}
