// k6 load test for the rate limiter running behind nginx (docker compose).
//
//   docker compose up -d --wait
//   docker run --rm -i --network rate_lim_default -e BASE_URL=http://nginx \
//     -v "$PWD/k6:/scripts" grafana/k6:2.3.0 run /scripts/load-test.js
//
// Three scenarios run one after another. Thresholds turn the expectations into pass/fail,
// so k6 exits non-zero if the limiter admits too much, too little, or answers incorrectly.

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 429 is an expected answer from a rate limiter, not a failed request
http.setResponseCallback(http.expectedStatuses(200, 202, 429));

const allowed = new Counter('rate_limit_allowed');
const limited = new Counter('rate_limit_rejected');
const unexpected = new Rate('unexpected_status');

export const options = {
  scenarios: {
    // 50 VUs share ONE user on /api/search (limit: 5, then 5 per minute).
    hot_user: {
      executor: 'constant-vus',
      exec: 'hotUser',
      vus: 50,
      duration: '10s',
    },
    // 200 users, each well under their own limit: nobody should be rejected.
    many_users: {
      executor: 'per-vu-iterations',
      exec: 'manyUsers',
      vus: 200,
      iterations: 3,
      startTime: '11s',
      maxDuration: '30s',
    },
    // 100 requests/s for 10s on /api/ping (per IP: burst 10, then 5/s), all from one client IP.
    ping_flood: {
      executor: 'constant-arrival-rate',
      exec: 'pingFlood',
      rate: 100,
      timeUnit: '1s',
      duration: '10s',
      preAllocatedVUs: 50,
      maxVUs: 200,
      startTime: '42s',
    },
  },
  thresholds: {
    // Hot user: exactly 5, plus at most 1 token refilled during the 10 s (5 per minute)
    'rate_limit_allowed{scenario:hot_user}': ['count>=5', 'count<=6'],
    // Many users: 3 requests each against a limit of 5 -> never rejected
    'rate_limit_rejected{scenario:many_users}': ['count==0'],
    // Ping flood: 1000 requests in 10 s against "10, then 5/s". Expected admissions differ by
    // algorithm: token bucket ~60 (burst 10 + 5/s), sliding log ~50 (10 per rolling 2 s window),
    // sliding counter slightly under 50 (its weighted estimate is conservative right after a burst).
    // The range accepts all three but fails if the flood gets through or everything is blocked.
    'rate_limit_allowed{scenario:ping_flood}': ['count>=40', 'count<=70'],
    // Only 200/202/429 are acceptable answers, and every check must pass
    unexpected_status: ['rate==0'],
    checks: ['rate==1'],
    // The limiter adds one Redis round trip; responses should stay fast
    'http_req_duration{scenario:many_users}': ['p(95)<250'],
  },
};

function record(res) {
  const ok = res.status === 200 || res.status === 202;
  const tooMany = res.status === 429;
  unexpected.add(!ok && !tooMany);
  if (ok) {
    allowed.add(1);
  } else if (tooMany) {
    limited.add(1);
  }

  check(res, {
    'has X-RateLimit-Limit': (r) => r.headers['X-Ratelimit-Limit'] !== undefined,
    'has X-RateLimit-Remaining': (r) => r.headers['X-Ratelimit-Remaining'] !== undefined,
    '429 has Retry-After >= 1': (r) => r.status !== 429 || Number(r.headers['Retry-After']) >= 1,
  });
}

// Runs once; its result is passed to every VU. (Top-level code runs separately in each VU,
// so an id computed there would differ per VU and split the "one user" into many.)
export function setup() {
  return { runId: Date.now() };
}

export function hotUser(data) {
  record(http.get(`${BASE_URL}/api/search?q=k6`, {
    headers: { 'X-User-Id': `k6-hot-${data.runId}` },
  }));
}

export function manyUsers(data) {
  record(http.get(`${BASE_URL}/api/search?q=k6`, {
    headers: { 'X-User-Id': `k6-user-${data.runId}-${__VU}` },
  }));
}

export function pingFlood() {
  record(http.get(`${BASE_URL}/api/ping`));
}
