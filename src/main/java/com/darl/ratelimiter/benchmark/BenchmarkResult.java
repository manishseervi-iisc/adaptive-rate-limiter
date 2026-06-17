package com.darl.ratelimiter.benchmark;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Timing and accuracy statistics for one benchmark run.
 */
@Getter
@Builder
public class BenchmarkResult {

    private final String algorithm;
    private final int    totalRequests;
    private final int    allowedCount;
    private final int    rejectedCount;

    /** Latencies in microseconds, sorted ascending */
    private final List<Long> latenciesMicros;

    private final long p50Micros;
    private final long p95Micros;
    private final long p99Micros;
    private final long maxMicros;
    private final long minMicros;
    private final long avgMicros;

    private final long durationMs;        // total wall-clock time
    private final double throughputRps;   // requests per second achieved

    public static BenchmarkResult compute(String algorithm,
                                          List<Long> latencies,
                                          int allowed, int rejected,
                                          long durationMs) {
        List<Long> sorted = latencies.stream().sorted().toList();
        int n = sorted.size();

        long p50 = sorted.get((int) (n * 0.50));
        long p95 = sorted.get((int) (n * 0.95));
        long p99 = sorted.get((int) (n * 0.99));
        long max = sorted.get(n - 1);
        long min = sorted.get(0);
        long avg = (long) sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        double rps = n / (durationMs / 1000.0);

        return BenchmarkResult.builder()
                .algorithm(algorithm)
                .totalRequests(n)
                .allowedCount(allowed)
                .rejectedCount(rejected)
                .latenciesMicros(sorted)
                .p50Micros(p50)
                .p95Micros(p95)
                .p99Micros(p99)
                .maxMicros(max)
                .minMicros(min)
                .avgMicros(avg)
                .durationMs(durationMs)
                .throughputRps(rps)
                .build();
    }
}
