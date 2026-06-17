package com.darl.ratelimiter.benchmark;

import com.darl.ratelimiter.model.ClientConfig;
import com.darl.ratelimiter.ratelimit.RateLimitResult;
import com.darl.ratelimiter.ratelimit.RateLimiter;
import com.darl.ratelimiter.ratelimit.RateLimiterFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Benchmarks all four rate limiting algorithms by sending N sequential
 * requests and recording per-request latency in microseconds.
 *
 * AI concept connection: the latency and throughput numbers from this
 * benchmark become features in the ML model — a client running GCRA
 * will have a different latency signature than one running Fixed Window,
 * and the model uses this to recommend algorithm switches.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BenchmarkService {

    private final RateLimiterFactory factory;

    /**
     * Run a benchmark for a single algorithm.
     *
     * @param algorithm     which algorithm to test
     * @param requests      total number of requests to send
     * @param limit         rate limit to apply
     * @param windowSeconds window size in seconds
     * @param burstSize     burst size for token bucket / GCRA
     */
    public BenchmarkResult run(ClientConfig.Algorithm algorithm,
                               int requests, int limit,
                               int windowSeconds, int burstSize) {

        RateLimiter limiter = factory.forAlgorithm(algorithm);
        // Unique clientId per run so previous state doesn't affect results
        String clientId = "bench-" + algorithm.name().toLowerCase()
                          + "-" + UUID.randomUUID().toString().substring(0, 8);

        List<Long> latencies = new ArrayList<>(requests);
        int allowed = 0, rejected = 0;

        log.info("[Benchmark] starting {} — {} requests, limit={}/{}s burst={}",
                algorithm, requests, limit, windowSeconds, burstSize);

        long wallStart = System.currentTimeMillis();

        for (int i = 0; i < requests; i++) {
            long t0 = System.nanoTime();
            RateLimitResult result = limiter.checkAndConsume(
                    clientId, limit, windowSeconds, burstSize);
            long micros = (System.nanoTime() - t0) / 1_000;

            latencies.add(micros);
            if (result.isAllowed()) allowed++; else rejected++;
        }

        long wallEnd = System.currentTimeMillis();
        long durationMs = wallEnd - wallStart;

        BenchmarkResult result = BenchmarkResult.compute(
                algorithm.name(), latencies, allowed, rejected, durationMs);

        log.info("[Benchmark] {} done — p50={}µs p99={}µs max={}µs rps={} allowed={} rejected={}",
                algorithm,
                result.getP50Micros(),
                result.getP99Micros(),
                result.getMaxMicros(),
                String.format("%.0f", result.getThroughputRps()),
                allowed, rejected);

        return result;
    }

    /**
     * Run benchmarks for ALL four algorithms with the same parameters
     * so results are directly comparable.
     */
    public List<BenchmarkResult> runAll(int requests, int limit,
                                        int windowSeconds, int burstSize) {
        List<BenchmarkResult> results = new ArrayList<>();
        for (ClientConfig.Algorithm algo : ClientConfig.Algorithm.values()) {
            results.add(run(algo, requests, limit, windowSeconds, burstSize));
        }
        return results;
    }
}
