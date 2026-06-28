/**
 * Sustained Load Test — TARGET_RPS for 5 minutes across all 4 client IDs.
 *
 * Goals:
 *   - Confirm p99 remains stable (no latency creep) over time.
 *   - Redis memory stays flat (GCRA keys have 60 s TTL — bounded growth).
 *   - Async audit writes don't back up the PostgreSQL queue.
 *
 * Thresholds default to 50 ms for Docker-on-Windows (~13 ms network overhead).
 * Use --env P99_MS=10 when running native k6 on the same host as the app.
 *
 * Run:
 *   Get-Content loadtest/sustained-load.js | docker run --rm -i grafana/k6 run `
 *     --env BASE_URL=http://host.docker.internal:8080 `
 *     --env TARGET_RPS=500 -
 */
import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// ── Custom metrics ────────────────────────────────────────────────────────────
const rateLimitedRate = new Rate('rate_limited_rate');
const errorRate       = new Rate('error_rate');
const allowedCounter  = new Counter('allowed_requests');
const rejectedCounter = new Counter('rejected_requests');

// ── Config ────────────────────────────────────────────────────────────────────
const BASE_URL    = __ENV.BASE_URL    || 'http://localhost:8080';
const TARGET_RPS  = parseInt(__ENV.TARGET_RPS  || '500', 10);
const DURATION    = __ENV.DURATION    || '5m';
const P99_MS      = parseInt(__ENV.P99_MS      || '50',  10);
const CLIENT_IDS  = ['client-1', 'client-2', 'client-3', 'client-4'];

// ── Scenario ──────────────────────────────────────────────────────────────────
export const options = {
    scenarios: {
        sustained: {
            executor:        'constant-arrival-rate',
            rate:            TARGET_RPS,
            timeUnit:        '1s',
            duration:        DURATION,
            preAllocatedVUs: 50,
            maxVUs:          200,
        },
    },

    thresholds: {
        'http_req_duration': [`p(99)<${P99_MS}`, `p(95)<${Math.round(P99_MS * 0.8)}`],
        'http_req_failed':   ['rate<0.005'],
        'error_rate':        ['rate<0.005'],
    },

    summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'p(99.9)', 'max'],
};

// ── Default function ──────────────────────────────────────────────────────────
export default function () {
    const clientId = CLIENT_IDS[__VU % CLIENT_IDS.length];
    const url      = `${BASE_URL}/api/v1/ratelimit/check?clientId=${clientId}`;

    const res = http.post(url, null, {
        tags:             { client: clientId, test: 'sustained' },
        timeout:          '5s',
        responseCallback: http.expectedStatuses(200, 429),
    });

    const isAllowed     = res.status === 200;
    const isRateLimited = res.status === 429;
    const isError       = !isAllowed && !isRateLimited;

    check(res, {
        'status 200 or 429':                    (r) => r.status === 200 || r.status === 429,
        'X-RateLimit-Limit header present':     (r) => r.headers['X-Ratelimit-Limit'] !== undefined,
        'X-RateLimit-Remaining header present': (r) => r.headers['X-Ratelimit-Remaining'] !== undefined,
        'X-RateLimit-Reset header present':     (r) => r.headers['X-Ratelimit-Reset'] !== undefined,
        'X-RateLimit-Algorithm header present': (r) => r.headers['X-Ratelimit-Algorithm'] !== undefined,
        'body is valid JSON':                   (r) => {
            try { JSON.parse(r.body); return true; } catch { return false; }
        },
    });

    if (isAllowed)     allowedCounter.add(1,  { client: clientId });
    if (isRateLimited) rejectedCounter.add(1, { client: clientId });

    rateLimitedRate.add(isRateLimited);
    errorRate.add(isError);
}

// ── Summary ───────────────────────────────────────────────────────────────────
export function handleSummary(data) {
    const d      = data.metrics.http_req_duration.values;
    const rps    = data.metrics.http_reqs.values['rate'].toFixed(1);
    const total  = data.metrics.http_reqs.values.count;
    const rl     = (data.metrics.rate_limited_rate.values.rate * 100).toFixed(1);
    const errPct = (data.metrics.error_rate.values.rate * 100).toFixed(2);

    console.log(`\n══ Sustained Load Test (${DURATION} @ ${TARGET_RPS} RPS) ══`);
    console.log(`  Total requests : ${total}`);
    console.log(`  Actual RPS     : ${rps}`);
    console.log(`  p50  latency   : ${d['med'].toFixed(2)} ms`);
    console.log(`  p95  latency   : ${d['p(95)'].toFixed(2)} ms`);
    console.log(`  p99  latency   : ${d['p(99)'].toFixed(2)} ms  (threshold: <${P99_MS} ms)`);
    console.log(`  p99.9 latency  : ${d['p(99.9)'].toFixed(2)} ms`);
    console.log(`  max latency    : ${d['max'].toFixed(2)} ms`);
    console.log(`  Rate limited   : ${rl}%`);
    console.log(`  Error rate     : ${errPct}%`);
    console.log('══════════════════════════════════════════════════\n');

    return {
        'loadtest/results/sustained-load-summary.json': JSON.stringify(data, null, 2),
        stdout: '\n',
    };
}
