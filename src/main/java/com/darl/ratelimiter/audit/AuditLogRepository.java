package com.darl.ratelimiter.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findByClientIdOrderByCreatedAtDesc(String clientId, Pageable pageable);

    long countByClientIdAndDecision(String clientId, AuditLog.Decision decision);

    @Query("""
        SELECT a.clientId, COUNT(a) as total,
               SUM(CASE WHEN a.decision = 'REJECTED' THEN 1 ELSE 0 END) as rejections
        FROM AuditLog a
        WHERE a.createdAt >= :since
        GROUP BY a.clientId
        ORDER BY rejections DESC
    """)
    List<Object[]> topRejectedClients(OffsetDateTime since);
}
