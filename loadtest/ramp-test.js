/**
 * Ramp Test — gradually scale to 1000 RPS across 4 client IDs.
 *
 * Thresholds are tuned for Docker-on-Windows where host.docker.internal
 * adds ~13 ms of fixed network overhead. For native k6 on the same host,
 * p99 will be ~3-5 ms; the GCRA Lua script itself runs in <1 ms on Redis.
 *
 * Run:
 *   Get-Content loadtest/ramp-test.js | docker run --rm -i grafana/k6 run `
 *     --env BASE_URL=http://host.docker.internal:8080 -
 *
 *   # Override thresholds for native k6 (no Docker overhead):
 *   k6 run --env P99_MS=10 loadtest/ramp-test.js
 */
import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// ── Custom metrics ────────────────────────────────────────────────────────────
const rateLimitedRate = new Rate('rate_limited_rate');
const errorRate       = new Rate('error_rate');         // true infra errors only
const checkLatency    = new Trend('check_latency_ms', true);

// ── Config ────────────────────────────────────────────────────────────────────
const BASE_URL   = __ENV.BASE_URL || 'http://localhost:8080';
const CLIENT_IDS = ['client-1', 'client-2', 'client-3', 'client-4'];
// Docker-on-Windows adds ~13 ms overhead; native k6 can use P99_MS=10
const P99_MS     = parseInt(__ENV.P99_MS || '50', 10);

// ── Scenario ──────────────────────────────────────────────────────────────────
export const options = {
    scenarios: {
        ramp_to_1000_rps: {
            executor:        'ramping-arrival-rate',
            startRate:       0,
            timeUnit:        '1s',
            preAllocatedVUs: 50,
            maxVUs:          300,
            stages: [
                { duration: '1m',  target: 250  },  // ramp 0 → 250 RPS
                { duration: '1m',  target: 500  },  // ramp 250 → 500 RPS
                { duration: '1m',  target: 750  },  // ramp 500 → 750 RPS
                { duration: '1m',  target: 1000 },  // ramp 750 → 1000 RPS
                { duration: '2m',  target: 1000 },  // hold at 1000 RPS
                { duration: '30s', target: 0    },  // ramp down
            ],
        },
    },

    thresholds: {
        [`http_req_duration{scenario:ramp_to_1000_rps}`]: [`p(99)<${P99_MS}`],
        // http_req_failed only counts true errors — 429 is excluded via responseCallback
        'http_req_failed': ['rate<0.01'],
        'error_rate':      ['rate<0.01'],
    },
};

// ── Default function ──────────────────────────────────────────────────────────
export default function () {
    const clientId = CLIENT_IDS[__VU % CLIENT_IDS.length];
    const url      = `${BASE_URL}/api/v1/ratelimit/check?clientId=${clientId}`;

    const res = http.post(url, null, {
        tags:             { client: clientId, test: 'ramp' },
        // Mark 429 as an expected status — k6 will NOT count it as http_req_failed
        responseCallback: http.expectedStatuses(200, 429),
    });

    const isAllowed     = res.status === 200;
    const isRateLimited = res.status === 429;
    const isError       = !isAllowed && !isRateLimited;

    check(res, {
        'status 200 or 429':             (r) => r.status === 200 || r.status === 429,
        'X-RateLimit-Limit present':     (r) => r.headers['X-Ratelimit-Limit'] !== undefined,
        'X-RateLimit-Remaining present': (r) => r.headers['X-Ratelimit-Remaining'] !== undefined,
        'X-RateLimit-Algorithm present': (r) => r.headers['X-Ratelimit-Algorithm'] !== undefined,
    });

    checkLatency.add(res.timings.duration, { client: clientId });
    rateLimitedRate.add(isRateLimited);
    errorRate.add(isError);
}

// ── Summary ───────────────────────────────────────────────────────────────────
export function handleSummary(data) {
    const d   = data.metrics.http_req_duration.values;
    const rps = data.metrics.http_reqs.values['rate'].toFixed(1);
    const rl  = (data.metrics.rate_limited_rate.values.rate * 100).toFixed(1);

    console.log('\n══ Ramp Test Results ════════════════════════════');
    console.log(`  Peak RPS    : ${rps}`);
    console.log(`  p50 latency : ${d['p(50)'].toFixed(2)} ms`);
    console.log(`  p95 latency : ${d['p(95)'].toFixed(2)} ms`);
    console.log(`  p99 latency : ${d['p(99)'].toFixed(2)} ms  (threshold: <${P99_MS} ms)`);
    console.log(`  Rate limited: ${rl}%`);
    console.log('════════════════════════════════════════════════\n');

    return {
        'loadtest/results/ramp-test-summary.json': JSON.stringify(data, null, 2),
        stdout: '\n',
    };
}
