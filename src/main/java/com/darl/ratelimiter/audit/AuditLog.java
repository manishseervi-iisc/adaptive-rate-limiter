package com.darl.ratelimiter.audit;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * Persistent audit record for every rate limit decision.
 * Written asynchronously — never blocks the hot path.
 * Maps to the {@code rate_limit_audit_log} PostgreSQL table.
 */
@Entity
@Table(name = "rate_limit_audit_log",
        indexes = {
            @Index(name = "idx_audit_client_created", columnList = "client_id,created_at DESC"),
            @Index(name = "idx_audit_decision",        columnList = "decision,created_at DESC")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false)
    private Decision decision;

    @Column(name = "algorithm", nullable = false)
    private String algorithm;

    @Column(name = "limit_applied", nullable = false)
    private int limitApplied;

    @Column(name = "current_count", nullable = false)
    private long currentCount;

    @Column(name = "redis_shard")
    private String redisShard;

    @Column(name = "trace_id")
    private String traceId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    public enum Decision {
        ALLOWED, REJECTED
    }
}
