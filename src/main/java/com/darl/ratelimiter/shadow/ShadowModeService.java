package com.darl.ratelimiter.shadow;

import com.darl.ratelimiter.config.AppProperties;
import com.darl.ratelimiter.model.ClientConfig;
import com.darl.ratelimiter.ratelimit.RateLimitResult;
import com.darl.ratelimiter.ratelimit.RateLimiter;
import com.darl.ratelimiter.ratelimit.RateLimiterFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Day 9: A/B shadow testing.
 *
 * On every request (when shadow-mode is enabled) this service replays the
 * rate-limit check in a separate Redis key namespace using the ML-predicted
 * adaptive limit. The primary decision and primary Redis counters are never
 * affected — shadow keys are prefixed with {@code shadow:} so they are
 * fully isolated.
 *
 * Results are written asynchronously to {@code ab_experiment_results}.
 * Divergences (static ALLOW vs adaptive REJECT or vice-versa) are logged
 * at INFO level so they can be tracked without querying the database.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShadowModeService {

    private final AbExperimentResultRepository experimentRepository;
    private final RateLimiterFactory rateLimiterFactory;
    private final AppProperties appProperties;

    /**
     * Run a shadow rate-limit check and persist the comparison record.
     *
     * @param clientId      the caller being rate-limited
     * @param primaryResult the decision that was returned to the caller
     * @param staticLimit   the limit used by the primary check
     * @param adaptiveLimit the ML-predicted limit (or fall-back to static)
     * @param config        the client's algorithm + window config
     */
    @Async("shadowExecutor")
    public void runShadow(String clientId,
                          RateLimitResult primaryResult,
                          int staticLimit,
                          int adaptiveLimit,
                          ClientConfig config) {
        try {
            // Run adaptive check in an isolated shadow key space so it does not
            // consume tokens from the live rate-limit counters.
            RateLimiter limiter = rateLimiterFactory.forAlgorithm(config.getAlgorithm());
            RateLimitResult shadowResult = limiter.checkAndConsume(
                    "shadow:" + clientId,
                    adaptiveLimit,
                    config.getWindowSeconds(),
                    adaptiveLimit
            );

            double divergencePct = staticLimit > 0
                    ? Math.abs(adaptiveLimit - staticLimit) * 100.0 / staticLimit
                    : 0.0;

            String decisionStatic   = primaryResult.isAllowed() ? "ALLOWED" : "REJECTED";
            String decisionAdaptive = shadowResult.isAllowed()  ? "ALLOWED" : "REJECTED";

            experimentRepository.save(AbExperimentResult.builder()
                    .experimentId(appProperties.getShadowMode().getExperimentId())
                    .clientId(clientId)
                    .staticLimit(staticLimit)
                    .adaptiveLimit(adaptiveLimit)
                    .divergencePct(divergencePct)
                    .actualRps(primaryResult.getCurrentCount() / (double) config.getWindowSeconds())
                    .decisionStatic(decisionStatic)
                    .decisionAdaptive(decisionAdaptive)
                    .build());

            if (!decisionStatic.equals(decisionAdaptive)) {
                log.info("[Shadow] DIVERGENCE clientId={} experimentId={} "
                                + "staticLimit={} adaptiveLimit={} divergencePct={}% "
                                + "staticDecision={} adaptiveDecision={}",
                        clientId,
                        appProperties.getShadowMode().getExperimentId(),
                        staticLimit, adaptiveLimit,
                        String.format("%.1f", divergencePct),
                        decisionStatic, decisionAdaptive);
            }

        } catch (Exception e) {
            log.warn("[Shadow] failed to record shadow result for {}: {}", clientId, e.getMessage());
        }
    }
}
