package com.darl.ratelimiter.ratelimit;

import com.darl.ratelimiter.model.ClientConfig;
import com.darl.ratelimiter.ratelimit.impl.FixedWindowRateLimiter;
import com.darl.ratelimiter.ratelimit.impl.GcraRateLimiter;
import com.darl.ratelimiter.ratelimit.impl.SlidingWindowRateLimiter;
import com.darl.ratelimiter.ratelimit.impl.TokenBucketRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Selects the correct {@link RateLimiter} implementation based on
 * the algorithm stored in the client's {@link ClientConfig}.
 *
 * In Day 6-7 the ML model will override this selection by returning
 * a recommended algorithm alongside the predicted limit.
 */
@Component
@RequiredArgsConstructor
public class RateLimiterFactory {

    private final SlidingWindowRateLimiter slidingWindow;
    private final TokenBucketRateLimiter   tokenBucket;
    private final FixedWindowRateLimiter   fixedWindow;
    private final GcraRateLimiter          gcra;

    public RateLimiter forAlgorithm(ClientConfig.Algorithm algorithm) {
        return switch (algorithm) {
            case SLIDING_WINDOW -> slidingWindow;
            case TOKEN_BUCKET   -> tokenBucket;
            case FIXED_WINDOW   -> fixedWindow;
            case GCRA           -> gcra;
        };
    }
}
