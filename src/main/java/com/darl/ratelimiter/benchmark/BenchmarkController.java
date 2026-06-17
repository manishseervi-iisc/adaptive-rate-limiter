package com.darl.ratelimiter.benchmark;

import com.darl.ratelimiter.model.ClientConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints to trigger and view benchmark results.
 *
 * GET /api/v1/benchmark/run?requests=1000&limit=500&window=60&burst=500
 *   → runs all 4 algorithms and returns comparison table
 *
 * GET /api/v1/benchmark/run/{algorithm}?requests=1000&limit=500
 *   → runs a single algorithm
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/benchmark")
@RequiredArgsConstructor
public class BenchmarkController {

    private final BenchmarkService benchmarkService;

    /**
     * Run all 4 algorithms and return a side-by-side comparison.
     */
    @GetMapping("/run")
    public Map<String, Object> runAll(
            @RequestParam(defaultValue = "1000") int requests,
            @RequestParam(defaultValue = "500")  int limit,
            @RequestParam(defaultValue = "60")   int window,
            @RequestParam(defaultValue = "500")  int burst) {

        log.info("[Benchmark API] runAll requests={} limit={} window={}s burst={}",
                requests, limit, window, burst);

        List<BenchmarkResult> results =
                benchmarkService.runAll(requests, limit, window, burst);

        return Map.of(
                "parameters", Map.of(
                        "requests", requests,
                        "limit",    limit,
                        "window",   window,
                        "burst",    burst
                ),
                "results", results.stream()
                        .map(this::toSummary)
                        .toList()
        );
    }

    /**
     * Run a single algorithm.
     */
    @GetMapping("/run/{algorithm}")
    public Map<String, Object> runSingle(
            @PathVariable String algorithm,
            @RequestParam(defaultValue = "1000") int requests,
            @RequestParam(defaultValue = "500")  int limit,
            @RequestParam(defaultValue = "60")   int window,
            @RequestParam(defaultValue = "500")  int burst) {

        ClientConfig.Algorithm algo =
                ClientConfig.Algorithm.valueOf(algorithm.toUpperCase());

        BenchmarkResult result =
                benchmarkService.run(algo, requests, limit, window, burst);

        return toSummary(result);
    }

    private Map<String, Object> toSummary(BenchmarkResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("algorithm",      r.getAlgorithm());
        m.put("totalRequests",  r.getTotalRequests());
        m.put("allowed",        r.getAllowedCount());
        m.put("rejected",       r.getRejectedCount());
        m.put("throughputRps",  String.format("%.0f", r.getThroughputRps()));
        m.put("latency", Map.of(
                "p50_micros", r.getP50Micros(),
                "p95_micros", r.getP95Micros(),
                "p99_micros", r.getP99Micros(),
                "max_micros", r.getMaxMicros(),
                "min_micros", r.getMinMicros(),
                "avg_micros", r.getAvgMicros()
        ));
        m.put("durationMs", r.getDurationMs());
        return m;
    }
}
