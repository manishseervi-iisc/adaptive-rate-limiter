package com.darl.ratelimiter.anomaly;

import com.darl.ratelimiter.audit.AuditLogRepository;
import com.darl.ratelimiter.metrics.RateLimitMetricsService;
import com.darl.ratelimiter.model.AnomalyEvent;
import com.darl.ratelimiter.storage.postgres.AnomalyEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Day 12: Unit tests for AnomalyDetector.
 * Validates Z-score logic and persistence without needing infrastructure.
 */
@ExtendWith(MockitoExtension.class)
class AnomalyDetectorTest {

    @Mock private AuditLogRepository auditLogRepository;
    @Mock private AnomalyEventRepository anomalyEventRepository;
    @Mock private RateLimitMetricsService metricsService;

    @InjectMocks
    private AnomalyDetector anomalyDetector;

    @Test
    @DisplayName("No anomaly is recorded when there is no traffic")
    void noAnomalyWithoutTraffic() {
        when(auditLogRepository.countByClientSince(any())).thenReturn(List.of());

        anomalyDetector.detect();

        verify(anomalyEventRepository, never()).save(any());
        verify(metricsService, never()).recordAnomalyEvent(anyString());
    }

    @Test
    @DisplayName("No anomaly for steady traffic (stddev below threshold)")
    void noAnomalyForSteadyTraffic() {
        // All identical values → stddev = 0 < 0.5, detector returns early
        when(auditLogRepository.countByClientSince(any()))
                .thenReturn(List.of(new Object[]{"client-1", 60L}));

        for (int i = 0; i < 8; i++) {
            anomalyDetector.detect();
        }

        verify(anomalyEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Anomaly recorded when Z-score exceeds 3-sigma threshold")
    void anomalyRecordedOnTrafficSpike() {
        // Build baseline of 25 alternating low/moderate calls (60 and 180 req / 60 s
        // → 1 rps and 3 rps). This produces mean≈2, stddev≈1 in the history.
        // Then inject a massive spike (60 000 req / 60 s = 1 000 rps).
        // With 26 history points the spike gives Z-score ≈ 5.0 > 3.0.
        AtomicInteger callIndex = new AtomicInteger(0);
        doAnswer(inv -> {
            int n = callIndex.getAndIncrement();
            long count = n < 25
                    ? (n % 2 == 0 ? 60L : 180L)  // alternating 1 rps / 3 rps
                    : 60_000L;                     // massive spike: 1000 rps
            return List.of(new Object[]{"client-spike", count});
        }).when(auditLogRepository).countByClientSince(any());

        for (int i = 0; i <= 25; i++) {
            anomalyDetector.detect();
        }

        ArgumentCaptor<AnomalyEvent> captor = ArgumentCaptor.forClass(AnomalyEvent.class);
        verify(anomalyEventRepository, atLeastOnce()).save(captor.capture());

        AnomalyEvent saved = captor.getValue();
        assertThat(saved.getClientId()).isEqualTo("client-spike");
        assertThat(saved.getZScore()).isGreaterThan(3.0);
        assertThat(saved.getRpsAtSpike()).isGreaterThan(500.0);
        assertThat(saved.getActionTaken()).isEqualTo(AnomalyEvent.ActionTaken.ALERTED);

        verify(metricsService, atLeastOnce()).recordAnomalyEvent("client-spike");
    }

    @Test
    @DisplayName("No anomaly when history is too short (below MIN_HISTORY_FOR_DETECTION)")
    void noAnomalyWithInsufficientHistory() {
        when(auditLogRepository.countByClientSince(any()))
                .thenReturn(List.of(new Object[]{"client-1", 60_000L}));

        // Only 4 calls — below MIN_HISTORY_FOR_DETECTION = 5
        for (int i = 0; i < 4; i++) {
            anomalyDetector.detect();
        }

        verify(anomalyEventRepository, never()).save(any());
    }
}
