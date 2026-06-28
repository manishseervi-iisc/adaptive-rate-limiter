package com.darl.ratelimiter.shadow;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AbExperimentResultRepository extends JpaRepository<AbExperimentResult, Long> {

    Page<AbExperimentResult> findByExperimentIdOrderByRecordedAtDesc(String experimentId, Pageable pageable);

    long countByExperimentId(String experimentId);

    /** Rows where static and adaptive decisions differ — native SQL for CASE support. */
    @Query(value = """
        SELECT COUNT(*) FROM ab_experiment_results
        WHERE experiment_id = :experimentId
          AND decision_static != decision_adaptive
    """, nativeQuery = true)
    long countDivergencesByExperimentId(@Param("experimentId") String experimentId);

    /** Per-client summary: total rows, avg divergence %, decision divergence count. */
    @Query(value = """
        SELECT client_id,
               COUNT(*)                                                           AS total,
               AVG(divergence_pct)                                                AS avg_divergence,
               SUM(CASE WHEN decision_static != decision_adaptive THEN 1 ELSE 0 END) AS divergences
        FROM ab_experiment_results
        WHERE experiment_id = :experimentId
        GROUP BY client_id
        ORDER BY divergences DESC
    """, nativeQuery = true)
    List<Object[]> summaryByClient(@Param("experimentId") String experimentId);

    /** Single-row aggregate for the whole experiment (returned as a 1-element list). */
    @Query(value = """
        SELECT COUNT(*),
               AVG(divergence_pct),
               SUM(CASE WHEN decision_static != decision_adaptive THEN 1 ELSE 0 END)
        FROM ab_experiment_results
        WHERE experiment_id = :experimentId
    """, nativeQuery = true)
    List<Object[]> experimentSummary(@Param("experimentId") String experimentId);
}
