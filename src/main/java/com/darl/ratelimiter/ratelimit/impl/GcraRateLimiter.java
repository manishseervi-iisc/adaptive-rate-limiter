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
 * Generic Cell Rate Algorithm (GCRA) — also known as the Virtual Scheduling Algorithm.
 *
 * How it works:
 * - Tracks a single value: the "theoretical arrival time" (TAT) of the next allowed request
 * - Each request is compared against TAT
 * - If arrival time >= TAT - burst_tolerance → allowed, TAT updated
 * - If arrival time < TAT - burst_tolerance → rejected
 *
 * Redis key: rl:gcra:{clientId} (single string value = TAT in nanoseconds)
 *
 * Why GCRA?
 * - Produces the smoothest traffic — no bursts at window boundaries
 * - Single Redis key per client (most memory efficient)
 * - AI concept: GCRA's TAT value is the best single feature for the
 *   anomaly detector — a sudden drop in TAT signals a traffic spike
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GcraRateLimiter implements RateLimiter {

    private final StringRedisTemplate redisTemplate;

    /**
     * Lua script implementing GCRA.
     *
     * KEYS[1] = TAT key
     * ARGV[1] = now in microseconds
     * ARGV[2] = emission interval (microseconds per request) = windowUs / limit
     * ARGV[3] = burst tolerance in microseconds = (burstSize - 1) * emissionInterval
     * ARGV[4] = TTL seconds
     *
     * Returns: { allowed (1/0), tat_microseconds, next_allowed_in_ms }
     */
    private static final String GCRA_SCRIPT = """
            local key              = KEYS[1]
            local now              = tonumber(ARGV[1])
            local emissionInterval = tonumber(ARGV[2])
            local burstTolerance   = tonumber(ARGV[3])
            local ttl              = tonumber(ARGV[4])
            
            local tat = tonumber(redis.call('GET', key)) or now
            
            -- The earliest time a new cell is allowed
            local newTat  = math.max(tat, now) + emissionInterval
            local allowed = (now >= tat - burstTolerance)
            
            if allowed then
                redis.call('SET', key, newTat, 'EX', ttl)
                local nextAllowedIn = math.max(0, math.floor((tat - burstTolerance - now) / 1000))
                return {1, newTat, nextAllowedIn}
            else
                local nextAllowedIn = math.ceil((tat - burstTolerance - now) / 1000)
                return {0, tat, nextAllowedIn}
            end
            """;

    private final DefaultRedisScript<List> script = new DefaultRedisScript<>(
            GCRA_SCRIPT, List.class
    );

    @Override
    public RateLimitResult checkAndConsume(String clientId, int limit,
                                           int windowSeconds, int burstSize) {
        String key = "rl:gcra:" + clientId;

        long nowUs = Instant.now().toEpochMilli() * 1000L;   // microseconds
        long windowUs = windowSeconds * 1_000_000L;
        long emissionInterval = windowUs / limit;             // microseconds per request
        long burstTolerance = (long) (burstSize - 1) * emissionInterval;
        int ttl = windowSeconds * 2;

        long resetAt = Instant.now().getEpochSecond() + windowSeconds;

        List result = redisTemplate.execute(
                script,
                List.of(key),
                String.valueOf(nowUs),
                String.valueOf(emissionInterval),
                String.valueOf(burstTolerance),
                String.valueOf(ttl)
        );

        boolean allowed = ((Number) result.get(0)).intValue() == 1;
        long tat = ((Number) result.get(1)).longValue();
        long nextAllowedInMs = ((Number) result.get(2)).longValue();

        log.debug("[GCRA] clientId={} allowed={} nextAllowedInMs={}", clientId, allowed, nextAllowedInMs);

        if (allowed) {
            return RateLimitResult.allowed(limit, limit - 1, resetAt, 1, algorithmName(), key);
        } else {
            return RateLimitResult.rejected(limit, resetAt + (nextAllowedInMs / 1000), tat, algorithmName(), key);
        }
    }

    @Override
    public String algorithmName() {
        return "GCRA";
    }
}
