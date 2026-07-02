import http from 'k6/http';
import { check } from 'k6';

// Local-laptop load-test artifact per DESIGN.md §15/§18: proves throughput methodology and that
// idempotency holds under real concurrency. Not framed as an enterprise-scale benchmark - a
// single docker-compose stack on one machine, sharing two accounts' row locks, will produce
// modest numbers by design (see the ledger's SELECT ... FOR UPDATE on both accounts in §10).
export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    throughput: {
      executor: 'constant-vus',
      vus: 10,
      duration: '30s',
      exec: 'throughputScenario',
    },
    idempotencyBurst: {
      executor: 'shared-iterations',
      vus: 20,
      iterations: 20,
      startTime: '35s',
      exec: 'idempotencyScenario',
    },
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SHARED_IDEMPOTENCY_KEY = __ENV.SHARED_IDEMPOTENCY_KEY || `loadtest-idempotency-burst-${Date.now()}`;

export function setup() {
  const res = http.post(`${BASE_URL}/auth/token`);
  const token = JSON.parse(res.body).token;
  return { token, sharedKey: SHARED_IDEMPOTENCY_KEY };
}

function authHeaders(token, idempotencyKey) {
  return {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
      Authorization: `Bearer ${token}`,
    },
  };
}

const PAYLOAD = JSON.stringify({
  fromAccount: 'jpy-funded',
  toAccount: 'jpy-low',
  amount: '1',
  currency: 'JPY',
});

export function throughputScenario(data) {
  const idempotencyKey = `loadtest-${__VU}-${__ITER}-${Date.now()}`;
  const res = http.post(`${BASE_URL}/payments`, PAYLOAD, authHeaders(data.token, idempotencyKey));
  check(res, { 'throughput: status is 201': (r) => r.status === 201 });
}

export function idempotencyScenario(data) {
  const res = http.post(`${BASE_URL}/payments`, PAYLOAD, authHeaders(data.token, data.sharedKey));
  check(res, { 'idempotency burst: status is 200 or 201': (r) => r.status === 200 || r.status === 201 });
}
