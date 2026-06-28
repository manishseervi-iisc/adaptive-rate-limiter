/**
 * Spike Test — sudden 10x traffic increase (100 → 1000 RPS).
 *
 * Verifies the rate limiter absorbs load spikes without latency degradation.
 * Thresholds use P99_MS env var (default 50 ms for Docker-on-Windows;
 * use P99_MS=10 for native k6 on the same host as the app).
 *
 * Run:
 *   Get-Content loadtest/spike-test.js | docker run --rm -i grafana/k6 run `
 *     --env BASE_URL=http://host.docker.internal:8080 -
 */
import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// ── Custom metrics ────────────────────────────────────────────────────────────
const rateLimitedRate  = new Rate('rate_limited_rate');
const errorRate        = new Rate('error_rate');
const spikeLatency     = new Trend('spike_phase_latency_ms',    true);
const baselineLatency  = new Trend('baseline_phase_latency_ms', true);
const totalRateLimited = new Counter('total_rate_limited');

// ── Config ────────────────────────────────────────────────────────────────────
const BASE_URL   = __ENV.BASE_URL || 'http://localhost:8080';
const CLIENT_IDS = ['client-1', 'client-2', 'client-3', 'client-4'];
const P99_MS     = parseInt(__ENV.P99_MS || '50', 10);

// ── Scenario ──────────────────────────────────────────────────────────────────
export const options = {
    scenarios: {
        spike: {
            executor:        'ramping-arrival-rate',
            startRate:       100,
            timeUnit:        '1s',
            preAllocatedVUs: 50,
            maxVUs:          500,
            stages: [
                { duration: '1m',  target: 100  },  // baseline:  100 RPS
                { duration: '15s', target: 1000 },  // spike onset: 10x jump
                { duration: '2m',  target: 1000 },  // spike sustained
                { duration: '15s', target: 100  },  // recovery
                { duration: '1m',  target: 100  },  // post-spike baseline
            ],
        },
    },

    thresholds: {
        'http_req_duration': [`p(99)<${P99_MS}`],
        'http_req_failed':   ['rate<0.01'],
        'error_rate':        ['rate<0.01'],
    },
};

// Spike phase: 60 s–195 s from test start
let startEpoch = 0;
function isSpikePhase() {
    const ms = Date.now() - startEpoch;
    return ms > 60_000 && ms < 195_000;
}

export function setup() {
    startEpoch = Date.now();
    return { startEpoch };
}

export default function (data) {
    if (startEpoch === 0) startEpoch = data.startEpoch;

    const clientId = CLIENT_IDS[__VU % CLIENT_IDS.length];
    const inSpike  = isSpikePhase();
    const url      = `${BASE_URL}/api/v1/ratelimit/check?clientId=${clientId}`;

    const res = http.post(url, null, {
        tags:             { client: clientId, phase: inSpike ? 'spike' : 'baseline' },
        responseCallback: http.expectedStatuses(200, 429),
    });

    const isAllowed     = res.status === 200;
    const isRateLimited = res.status === 429;
    const isError       = !isAllowed && !isRateLimited;

    check(res, {
        'status 200 or 429':       (r) => r.status === 200 || r.status === 429,
        'algorithm header present': (r) => r.headers['X-Ratelimit-Algorithm'] !== undefined,
    });

    if (inSpike) {
        spikeLatency.add(res.timings.duration, { client: clientId });
    } else {
        baselineLatency.add(res.timings.duration, { client: clientId });
    }

    rateLimitedRate.add(isRateLimited);
    errorRate.add(isError);
    if (isRateLimited) totalRateLimited.add(1);
}

// ── Summary ───────────────────────────────────────────────────────────────────
export function handleSummary(data) {
    const p99  = data.metrics.http_req_duration.values['p(99)'].toFixed(2);
    const p99s = data.metrics['spike_phase_latency_ms']?.values['p(99)']?.toFixed(2) ?? 'n/a';
    const p99b = data.metrics['baseline_phase_latency_ms']?.values['p(99)']?.toFixed(2) ?? 'n/a';
    const rl   = (data.metrics.rate_limited_rate.values.rate * 100).toFixed(1);
    const tot  = data.metrics.http_reqs.values.count;

    console.log('\n══ Spike Test Results ═══════════════════════════');
    console.log(`  Total requests   : ${tot}`);
    console.log(`  p99 overall      : ${p99} ms  (threshold: <${P99_MS} ms)`);
    console.log(`  p99 baseline     : ${p99b} ms`);
    console.log(`  p99 during spike : ${p99s} ms`);
    console.log(`  Rate limited     : ${rl}%`);
    console.log('════════════════════════════════════════════════\n');

    return {
        'loadtest/results/spike-test-summary.json': JSON.stringify(data, null, 2),
        stdout: '\n',
    };
}
