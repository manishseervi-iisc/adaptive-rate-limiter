package com.darl.ratelimiter.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Persistent rate limit configuration for a single client.
 * Maps to the {@code client_configs} PostgreSQL table.
 *
 * This is the source of truth: the service reads these on startup
 * and writes the active limit into Redis for hot-path access.
 */
@Entity
@Table(name = "client_configs",
        indexes = {
            @Index(name = "idx_client_configs_client_id", columnList = "client_id", unique = true),
            @Index(name = "idx_client_configs_active",    columnList = "is_active")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClientConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Identifies the caller — API key, user ID, IP, or service name. */
    @Column(name = "client_id", nullable = false, unique = true)
    @NotBlank
    private String clientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "algorithm", nullable = false)
    @NotNull
    @Builder.Default
    private Algorithm algorithm = Algorithm.SLIDING_WINDOW;

    /** Sustained requests per second allowed. */
    @Column(name = "rate_per_second", nullable = false)
    @Min(1)
    @Builder.Default
    private int ratePerSecond = 100;

    /** Maximum burst (token bucket only). */
    @Column(name = "burst_size", nullable = false)
    @Min(1)
    @Builder.Default
    private int burstSize = 200;

    /** Window length in seconds (sliding / fixed window). */
    @Column(name = "window_seconds", nullable = false)
    @Min(1)
    @Builder.Default
    private int windowSeconds = 60;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    // ── Inner enum ─────────────────────────────────────────────
    public enum Algorithm {
        TOKEN_BUCKET,
        SLIDING_WINDOW,
        FIXED_WINDOW,
        GCRA
    }
}
