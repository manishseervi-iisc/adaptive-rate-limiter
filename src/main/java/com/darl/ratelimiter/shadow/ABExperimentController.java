package com.darl.ratelimiter.shadow;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Day 9: REST API for A/B shadow experiment results.
 *
 * GET /api/v1/experiment/results?experimentId=exp-001
 * GET /api/v1/experiment/results/summary?experimentId=exp-001
 */
@RestController
@RequestMapping("/api/v1/experiment")
@RequiredArgsConstructor
public class ABExperimentController {

    private final AbExperimentResultRepository experimentRepository;

    /** Paginated raw results for a given experiment. */
    @GetMapping("/results")
    public Page<AbExperimentResult> getResults(
            @RequestParam String experimentId,
            @PageableDefault(size = 50) Pageable pageable) {
        return experimentRepository.findByExperimentIdOrderByRecordedAtDesc(experimentId, pageable);
    }

    /**
     * Aggregate summary for a given experiment:
     * - total records
     * - average divergence %
     * - count of decisions where static != adaptive
     * - per-client breakdown
     */
    @GetMapping("/results/summary")
    public ResponseEntity<Map<String, Object>> getSummary(@RequestParam String experimentId) {
        List<Object[]> aggRows = experimentRepository.experimentSummary(experimentId);

        if (aggRows == null || aggRows.isEmpty() || aggRows.get(0)[0] == null) {
            return ResponseEntity.ok(Map.of(
                    "experimentId", experimentId,
                    "totalRecords", 0,
                    "message", "No data recorded yet"
            ));
        }

        Object[] agg    = aggRows.get(0);
        long total       = ((Number) agg[0]).longValue();
        double avgDiv    = agg[1] != null ? ((Number) agg[1]).doubleValue() : 0.0;
        long divergences = agg[2] != null ? ((Number) agg[2]).longValue() : 0L;

        List<Map<String, Object>> perClient = new ArrayList<>();
        for (Object[] row : experimentRepository.summaryByClient(experimentId)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("clientId",         row[0]);
            entry.put("total",            ((Number) row[1]).longValue());
            entry.put("avgDivergencePct", row[2] != null ? ((Number) row[2]).doubleValue() : 0.0);
            entry.put("divergences",      ((Number) row[3]).longValue());
            perClient.add(entry);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("experimentId",        experimentId);
        summary.put("totalRecords",        total);
        summary.put("avgDivergencePct",    Math.round(avgDiv * 10.0) / 10.0);
        summary.put("decisionDivergences", divergences);
        summary.put("divergenceRatePct",   total > 0 ? Math.round(divergences * 1000.0 / total) / 10.0 : 0.0);
        summary.put("perClient",           perClient);
        return ResponseEntity.ok(summary);
    }
}
