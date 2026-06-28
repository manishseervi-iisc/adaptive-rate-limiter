package com.darl.ratelimiter.shadow;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * Persistent record of one shadow-mode A/B comparison.
 * Maps to the {@code ab_experiment_results} table (created in V1 migration).
 * Written asynchronously by {@link ShadowModeService}.
 */
@Entity
@Table(name = "ab_experiment_results",
        indexes = @Index(name = "idx_ab_experiment_id", columnList = "experiment_id,recorded_at DESC"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AbExperimentResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "experiment_id", nullable = false)
    private String experimentId;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @CreationTimestamp
    @Column(name = "recorded_at", updatable = false)
    private OffsetDateTime recordedAt;

    @Column(name = "static_limit", nullable = false)
    private int staticLimit;

    @Column(name = "adaptive_limit", nullable = false)
    private int adaptiveLimit;

    /** |adaptive - static| / static * 100 */
    @Column(name = "divergence_pct")
    private Double divergencePct;

    @Column(name = "actual_rps")
    private Double actualRps;

    @Column(name = "decision_static")
    private String decisionStatic;

    @Column(name = "decision_adaptive")
    private String decisionAdaptive;
}
