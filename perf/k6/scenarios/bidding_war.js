import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

const errorRate = new Rate('errors');
const bidLatency = new Trend('bid_latency', true);
const conflicts = new Counter('bid_conflicts');
const accepts = new Counter('bid_accepts');

export const options = {
  scenarios: {
    bidding_spike: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 500 },   // all 500 carriers ramp up
        { duration: '2m', target: 500 },    // sustained bidding
        { duration: '30s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<200', 'p(99)<500'],
    errors: ['rate<0.01'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8090';

export function setup() {
  // Create 50 loads for bidding
  const loadIds = [];
  for (let i = 0; i < 50; i++) {
    const res = http.post(`${BASE_URL}/loads`, JSON.stringify({
      tenantId: '00000000-0000-0000-0000-000000000001',
      origin: `City-${i}`,
      destination: `City-${i + 50}`,
      cargoType: 'GENERAL',
      weightKg: 10000 + i * 100,
      maxRateCents: 50000 + i * 1000,
      corridorId: `I-${(i % 20) * 5 + 5}`,
      corridorRisk: 30 + (i % 50),
    }), { headers: { 'Content-Type': 'application/json' } });
    if (res.status === 201) {
      const body = JSON.parse(res.body);
      loadIds.push(body.id);
    }
  }
  return { loadIds };
}

export default function (data) {
  const loadId = data.loadIds[__VU % data.loadIds.length];
  const carrierId = `00000000-0000-0000-0000-${String(__VU).padStart(12, '0')}`;
  const rate = 40000 + Math.floor(Math.random() * 20000);

  const res = http.post(`${BASE_URL}/bids`, JSON.stringify({
    loadId: loadId,
    carrierId: carrierId,
    rateCents: rate,
    estimatedHours: 20 + Math.random() * 30,
    riskAcknowledgment: true,
  }), {
    headers: {
      'Content-Type': 'application/json',
      'X-Request-Id': `bid-${__VU}-${__ITER}`,
    },
  });

  bidLatency.add(res.timings.duration);

  const ok = check(res, {
    'status is 202 or 409': (r) => r.status === 202 || r.status === 409,
  });

  if (res.status === 409) conflicts.add(1);
  errorRate.add(!ok);

  sleep(0.5);
}
