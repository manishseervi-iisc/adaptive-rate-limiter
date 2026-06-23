package com.darl.ratelimiter.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * Persistent record of a detected traffic spike.
 * Maps to the {@code anomaly_events} PostgreSQL table (created in V1 migration).
 * Written asynchronously by {@link com.darl.ratelimiter.anomaly.AnomalyDetector}.
 */
@Entity
@Table(name = "anomaly_events",
        indexes = @Index(name = "idx_anomaly_client", columnList = "client_id,detected_at DESC"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnomalyEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @CreationTimestamp
    @Column(name = "detected_at", updatable = false)
    private OffsetDateTime detectedAt;

    @Column(name = "z_score", nullable = false)
    private double zScore;

    @Column(name = "rps_at_spike", nullable = false)
    private double rpsAtSpike;

    @Column(name = "rolling_mean", nullable = false)
    private double rollingMean;

    @Column(name = "rolling_stddev", nullable = false)
    private double rollingStddev;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_taken")
    @Builder.Default
    private ActionTaken actionTaken = ActionTaken.ALERTED;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    public enum ActionTaken {
        TIGHTENED, ALERTED, NONE
    }
}
