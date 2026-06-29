package com.darl.ratelimiter.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * REST API for rate limit checks.
 *
 * POST /api/v1/ratelimit/check?clientId=xxx
 *   → 200 OK      (allowed)
 *   → 429 Too Many Requests (rejected)
 *
 * Standard rate limit response headers:
 *   X-RateLimit-Limit     — the configured limit
 *   X-RateLimit-Remaining — requests left in current window
 *   X-RateLimit-Reset     — epoch second when window resets
 *   X-RateLimit-Algorithm — which algorithm was used
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ratelimit")
@RequiredArgsConstructor
public class RateLimitController {

    private final RateLimitService rateLimitService;

    @PostMapping("/check")
    public ResponseEntity<Map<String, Object>> check(
            @RequestParam String clientId) {

        MDC.put("trace_id",  UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        MDC.put("client_id", clientId);
        try {
            return doCheck(clientId);
        } finally {
            MDC.clear();
        }
    }

    private ResponseEntity<Map<String, Object>> doCheck(String clientId) {
        RateLimitResult result = rateLimitService.checkLimit(clientId);
        MDC.put("algorithm", result.getAlgorithm());

        HttpHeaders headers = buildHeaders(result);

        if (result.isAllowed()) {
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(Map.of(
                            "allowed",    true,
                            "clientId",   clientId,
                            "remaining",  result.getRemaining(),
                            "resetAt",    result.getResetAtEpochSecond(),
                            "algorithm",  result.getAlgorithm()
                    ));
        } else {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .headers(headers)
                    .body(Map.of(
                            "allowed",   false,
                            "clientId",  clientId,
                            "remaining", 0,
                            "resetAt",   result.getResetAtEpochSecond(),
                            "algorithm", result.getAlgorithm(),
                            "message",   "Rate limit exceeded"
                    ));
        }
    }

    /**
     * GET /api/v1/ratelimit/check — convenience for browser testing
     */
    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> checkGet(
            @RequestParam String clientId) {
        return check(clientId);
    }

    private HttpHeaders buildHeaders(RateLimitResult result) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-RateLimit-Limit",     String.valueOf(result.getLimit()));
        headers.set("X-RateLimit-Remaining", String.valueOf(result.getRemaining()));
        headers.set("X-RateLimit-Reset",     String.valueOf(result.getResetAtEpochSecond()));
        headers.set("X-RateLimit-Algorithm", result.getAlgorithm());
        return headers;
    }
}
