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
 * Token Bucket algorithm.
 *
 * How it works:
 * - A bucket starts full (burstSize tokens)
 * - Tokens refill at rate = limit tokens per windowSeconds
 * - Each request consumes 1 token
 * - If bucket is empty → rejected
 *
 * Redis key: rl:tokenbucket:{clientId} (Hash with fields: tokens, last_refill)
 *
 * AI concept: token bucket is the best algorithm for burst-tolerant
 * adaptive limits — the ML model can independently tune both the
 * refill rate (sustained RPS) and the burst size.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenBucketRateLimiter implements RateLimiter {

    private final StringRedisTemplate redisTemplate;

    /**
     * Lua script — atomically refills tokens based on elapsed time,
     * then attempts to consume one token.
     *
     * KEYS[1] = hash key
     * ARGV[1] = current timestamp (seconds)
     * ARGV[2] = refill rate (tokens per second) = limit / windowSeconds
     * ARGV[3] = burst size (max tokens)
     * ARGV[4] = TTL seconds
     *
     * Returns: { allowed (1/0), current_tokens_after }
     */
    private static final String TOKEN_BUCKET_SCRIPT = """
            local key         = KEYS[1]
            local now         = tonumber(ARGV[1])
            local refillRate  = tonumber(ARGV[2])
            local burstSize   = tonumber(ARGV[3])
            local ttl         = tonumber(ARGV[4])
            
            local data = redis.call('HMGET', key, 'tokens', 'last_refill')
            local tokens     = tonumber(data[1]) or burstSize
            local lastRefill = tonumber(data[2]) or now
            
            -- Calculate tokens to add based on elapsed time
            local elapsed = math.max(0, now - lastRefill)
            local newTokens = math.min(burstSize, tokens + elapsed * refillRate)
            
            if newTokens >= 1 then
                -- Allow: consume one token
                newTokens = newTokens - 1
                redis.call('HMSET', key, 'tokens', newTokens, 'last_refill', now)
                redis.call('EXPIRE', key, ttl)
                return {1, math.floor(newTokens)}
            else
                -- Reject: update last_refill to now anyway
                redis.call('HMSET', key, 'tokens', newTokens, 'last_refill', now)
                redis.call('EXPIRE', key, ttl)
                return {0, math.floor(newTokens)}
            end
            """;

    private final DefaultRedisScript<List> script = new DefaultRedisScript<>(
            TOKEN_BUCKET_SCRIPT, List.class
    );

    @Override
    public RateLimitResult checkAndConsume(String clientId, int limit,
                                           int windowSeconds, int burstSize) {
        String key = "rl:tokenbucket:" + clientId;
        long nowSecs = Instant.now().getEpochSecond();
        double refillRate = (double) limit / windowSeconds;
        long resetAt = nowSecs + windowSeconds;
        int ttl = windowSeconds * 2;

        List result = redisTemplate.execute(
                script,
                List.of(key),
                String.valueOf(nowSecs),
                String.valueOf(refillRate),
                String.valueOf(burstSize),
                String.valueOf(ttl)
        );

        boolean allowed = ((Number) result.get(0)).intValue() == 1;
        long tokens = ((Number) result.get(1)).longValue();

        log.debug("[TokenBucket] clientId={} allowed={} tokens_remaining={}", clientId, allowed, tokens);

        if (allowed) {
            return RateLimitResult.allowed(burstSize, (int) tokens, resetAt, burstSize - tokens, algorithmName(), key);
        } else {
            return RateLimitResult.rejected(burstSize, resetAt, tokens, algorithmName(), key);
        }
    }

    @Override
    public String algorithmName() {
        return "TOKEN_BUCKET";
    }
}
