package com.darl.ratelimiter.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration bound to the {@code darl.rate-limiter.*} namespace.
 * IntelliJ will autocomplete these keys in application.yml thanks to
 * the spring-boot-configuration-processor dependency.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "darl.rate-limiter")
public class AppProperties {

    /** Default algorithm used when no client-specific config is found in PostgreSQL. */
    @NotBlank
    private String defaultAlgorithm = "SLIDING_WINDOW";

    /** Baseline requests/second for new clients without a config entry. */
    @Min(1)
    private int defaultRatePerSecond = 100;

    /** Max burst size for token bucket algorithm. */
    @Min(1)
    private int defaultBurstSize = 200;

    /** Window length in seconds for sliding/fixed window algorithms. */
    @Min(1)
    private int defaultWindowSeconds = 60;
}
