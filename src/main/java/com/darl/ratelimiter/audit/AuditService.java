package com.darl.ratelimiter.audit;

import com.darl.ratelimiter.ratelimit.RateLimitResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Writes rate limit decisions to PostgreSQL asynchronously.
 *
 * The @Async annotation means this runs on a separate thread pool —
 * the HTTP request thread never waits for the DB write.
 *
 * AI concept: these audit records are the raw training data for the
 * ML model. The model will query rate_limit_audit_log to build
 * (timestamp, client_id, rps, was_throttled) feature vectors.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    @Async("auditExecutor")
    public void record(String clientId, RateLimitResult result) {
        try {
            AuditLog entry = AuditLog.builder()
                    .clientId(clientId)
                    .decision(result.isAllowed()
                            ? AuditLog.Decision.ALLOWED
                            : AuditLog.Decision.REJECTED)
                    .algorithm(result.getAlgorithm())
                    .limitApplied(result.getLimit())
                    .currentCount(result.getCurrentCount())
                    .redisShard("localhost:6379")
                    .build();

            auditLogRepository.save(entry);

        } catch (Exception e) {
            log.warn("[Audit] failed to write record for {}: {}", clientId, e.getMessage());
        }
    }
}
