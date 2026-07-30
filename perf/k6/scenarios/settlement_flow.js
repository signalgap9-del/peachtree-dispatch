import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// ── Custom metrics ──
const errorRate = new Rate('errors');
const sagaCompletion = new Rate('saga_completed');
const flowDuration = new Trend('e2e_flow_duration', true);
const orphanedPending = new Counter('orphaned_pending');

export const options = {
  scenarios: {
    settlement_e2e: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '3m', target: 100 },   // ramp to 100 concurrent flows
        { duration: '5m', target: 100 },   // sustained load
        { duration: '1m', target: 0 },     // cool-down
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    errors: ['rate<0.02'],
    saga_completed: ['rate>0.95'],         // ≥95% sagas reach terminal state
    orphaned_pending: ['count<5'],         // near-zero orphaned PENDING
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8090';
const TENANT_ID = '00000000-0000-0000-0000-000000000001';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

/**
 * Each VU iteration runs a full settlement lifecycle:
 *   create load → submit bid → accept bid → simulate delivery → verify settlement
 */
export default function () {
  const flowStart = Date.now();
  const carrierId = `00000000-0000-0000-0000-${String(__VU).padStart(12, '0')}`;
  const requestId = `settle-${__VU}-${__ITER}`;

  // ── Step 1: Create a load ──
  let loadId;
  group('create_load', () => {
    const res = http.post(`${BASE_URL}/loads`, JSON.stringify({
      tenantId: TENANT_ID,
      origin: `Origin-${__VU}-${__ITER}`,
      destination: `Dest-${__VU}-${__ITER}`,
      cargoType: 'GENERAL',
      weightKg: 8000 + Math.floor(Math.random() * 12000),
      maxRateCents: 60000 + Math.floor(Math.random() * 40000),
      corridorId: `I-${(__VU % 20) * 5 + 5}`,
      corridorRisk: 20 + (__VU % 60),
    }), { headers: { ...JSON_HEADERS, 'X-Request-Id': `${requestId}-load` } });

    const ok = check(res, { 'load created (201)': (r) => r.status === 201 });
    errorRate.add(!ok);
    if (ok) {
      loadId = JSON.parse(res.body).id;
    }
  });

  if (!loadId) { sleep(1); return; }

  // ── Step 2: Submit a bid ──
  let bidId;
  group('submit_bid', () => {
    const res = http.post(`${BASE_URL}/bids`, JSON.stringify({
      loadId: loadId,
      carrierId: carrierId,
      rateCents: 45000 + Math.floor(Math.random() * 20000),
      estimatedHours: 18 + Math.random() * 36,
      riskAcknowledgment: true,
    }), { headers: { ...JSON_HEADERS, 'X-Request-Id': `${requestId}-bid` } });

    const ok = check(res, { 'bid accepted (202)': (r) => r.status === 202 });
    errorRate.add(!ok);
    if (ok) {
      bidId = JSON.parse(res.body).id;
    }
  });

  if (!bidId) { sleep(1); return; }

  // ── Step 3: Accept the bid (shipper selects carrier) ──
  group('accept_bid', () => {
    const res = http.post(`${BASE_URL}/bids/${bidId}/accept`, JSON.stringify({
      loadId: loadId,
      acceptedBy: TENANT_ID,
    }), { headers: { ...JSON_HEADERS, 'X-Request-Id': `${requestId}-accept` } });

    const ok = check(res, {
      'bid accept (200 or 202)': (r) => r.status === 200 || r.status === 202,
    });
    errorRate.add(!ok);
  });

  // ── Step 4: Simulate delivery completion ──
  group('complete_delivery', () => {
    const res = http.post(`${BASE_URL}/tracking/${loadId}/deliver`, JSON.stringify({
      deliveredAt: new Date().toISOString(),
      condition: 'INTACT',
      signedBy: `Receiver-${__VU}`,
    }), { headers: { ...JSON_HEADERS, 'X-Request-Id': `${requestId}-deliver` } });

    // Delivery endpoint may return 200, 202, or 204
    const ok = check(res, {
      'delivery recorded': (r) => [200, 202, 204].includes(r.status),
    });
    errorRate.add(!ok);
  });

  // ── Step 5: Verify settlement reached terminal state ──
  group('verify_settlement', () => {
    // Poll settlement status (saga may still be in-flight)
    let settled = false;
    for (let attempt = 0; attempt < 5; attempt++) {
      const res = http.get(`${BASE_URL}/settlements?loadId=${loadId}`, {
        headers: { 'X-Request-Id': `${requestId}-settle-${attempt}` },
      });

      if (res.status === 200) {
        const body = JSON.parse(res.body);
        const settlements = Array.isArray(body) ? body : (body.content || [body]);
        const terminal = settlements.find(
          (s) => s.status === 'COMPLETED' || s.status === 'DISPUTED'
        );
        if (terminal) {
          settled = true;
          sagaCompletion.add(true);
          break;
        }
      }
      sleep(2); // wait for saga to progress
    }

    if (!settled) {
      sagaCompletion.add(false);
      orphanedPending.add(1);
    }
  });

  flowDuration.add(Date.now() - flowStart);
  sleep(1);
}
