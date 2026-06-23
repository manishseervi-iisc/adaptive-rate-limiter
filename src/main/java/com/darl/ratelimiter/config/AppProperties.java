package com.darl.ratelimiter.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration bound to the {@code darl.*} namespace.
 * IntelliJ will autocomplete these keys in application.yml thanks to
 * the spring-boot-configuration-processor dependency.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "darl")
public class AppProperties {

    private RateLimiter rateLimiter = new RateLimiter();
    private Adaptive adaptive = new Adaptive();
    private ShadowMode shadowMode = new ShadowMode();

    // ── Nested: rate-limiter ──────────────────────────────────────────────────

    @Getter
    @Setter
    public static class RateLimiter {
        @NotBlank
        private String defaultAlgorithm = "GCRA";
        @Min(1)
        private int defaultRatePerSecond = 100;
        @Min(1)
        private int defaultBurstSize = 200;
        @Min(1)
        private int defaultWindowSeconds = 60;
    }

    // ── Nested: adaptive ML layer ─────────────────────────────────────────────

    @Getter
    @Setter
    public static class Adaptive {
        private boolean enabled = true;
        private String inferenceUrl = "http://localhost:8000";
        private long cacheTtlSeconds = 300;
        private int circuitBreakerThreshold = 5;
    }

    // ── Nested: shadow / A-B mode ─────────────────────────────────────────────

    @Getter
    @Setter
    public static class ShadowMode {
        private boolean enabled = false;
        private String experimentId = "exp-001";
    }

    // ── Legacy accessors (backwards compat with existing callers) ─────────────

    public String getDefaultAlgorithm()    { return rateLimiter.defaultAlgorithm; }
    public int    getDefaultRatePerSecond() { return rateLimiter.defaultRatePerSecond; }
    public int    getDefaultBurstSize()     { return rateLimiter.defaultBurstSize; }
    public int    getDefaultWindowSeconds() { return rateLimiter.defaultWindowSeconds; }
}
