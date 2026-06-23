package com.darl.ratelimiter.anomaly;

import com.darl.ratelimiter.audit.AuditLogRepository;
import com.darl.ratelimiter.model.AnomalyEvent;
import com.darl.ratelimiter.storage.postgres.AnomalyEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Day 8: Detects traffic spike anomalies using a Z-score test on rolling RPS.
 *
 * Every 10 seconds it queries the audit log for the last 60 s of records,
 * computes per-client RPS, and maintains a 30-point rolling history
 * (~5 minutes at 10-second granularity). When the current RPS deviates
 * more than 3 standard deviations from the rolling mean, a record is written
 * to {@code anomaly_events}.
 *
 * The @Scheduled method already runs on a background scheduler thread,
 * so DB writes here never touch the HTTP request path.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnomalyDetector {

    private static final double Z_SCORE_THRESHOLD = 3.0;
    private static final int HISTORY_SIZE = 30;
    private static final int MIN_HISTORY_FOR_DETECTION = 5;

    private final AuditLogRepository auditLogRepository;
    private final AnomalyEventRepository anomalyEventRepository;

    /** Per-client sliding window of observed RPS values (last ~5 min). */
    private final ConcurrentHashMap<String, Deque<Double>> rpsHistory = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 10_000)
    public void detect() {
        OffsetDateTime since = OffsetDateTime.now().minusSeconds(60);
        List<Object[]> counts = auditLogRepository.countByClientSince(since);

        for (Object[] row : counts) {
            String clientId = (String) row[0];
            double currentRps = ((Number) row[1]).longValue() / 60.0;
            processClient(clientId, currentRps);
        }
    }

    private void processClient(String clientId, double currentRps) {
        Deque<Double> history = rpsHistory.computeIfAbsent(clientId, k -> new ArrayDeque<>());
        history.addLast(currentRps);
        if (history.size() > HISTORY_SIZE) {
            history.pollFirst();
        }

        if (history.size() < MIN_HISTORY_FOR_DETECTION) {
            return;
        }

        double mean = mean(history);
        double stddev = stddev(history, mean);

        if (stddev < 0.5) {
            return; // traffic too steady for meaningful Z-scores
        }

        double zScore = (currentRps - mean) / stddev;
        if (zScore > Z_SCORE_THRESHOLD) {
            log.warn("[Anomaly] clientId={} z={} rps={} mean={} stddev={}",
                    clientId,
                    String.format("%.2f", zScore),
                    String.format("%.1f", currentRps),
                    String.format("%.1f", mean),
                    String.format("%.1f", stddev));
            persistAnomaly(clientId, zScore, currentRps, mean, stddev);
        }
    }

    private void persistAnomaly(String clientId, double zScore, double rpsAtSpike,
                                 double rollingMean, double rollingStddev) {
        try {
            anomalyEventRepository.save(
                    AnomalyEvent.builder()
                            .clientId(clientId)
                            .zScore(zScore)
                            .rpsAtSpike(rpsAtSpike)
                            .rollingMean(rollingMean)
                            .rollingStddev(rollingStddev)
                            .actionTaken(AnomalyEvent.ActionTaken.ALERTED)
                            .build()
            );
        } catch (Exception e) {
            log.warn("[Anomaly] failed to persist event for {}: {}", clientId, e.getMessage());
        }
    }

    private double mean(Deque<Double> values) {
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.size();
    }

    private double stddev(Deque<Double> values, double mean) {
        double sumSq = 0;
        for (double v : values) sumSq += (v - mean) * (v - mean);
        return Math.sqrt(sumSq / values.size());
    }
}
