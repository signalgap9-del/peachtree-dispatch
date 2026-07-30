// Fleet telemetry load profile (Phase 2).
//
// Simulates 10,000 trucks sending GPS pings to the telemetry ingest
// endpoint. Ramps from 0 to 5k VUs, holds, spikes to 10k, holds at
// peak, then ramps down. Validates that the ingest pipeline sustains
// sub-100ms p95 under peak fleet load.
//
// Usage:
//   k6 run perf/k6/scenarios/fleet_telemetry.js -e BASE_URL=http://localhost:8090
//
// Environment variables:
//   BASE_URL  - Telemetry API base URL (default: http://localhost:8090)
//   MAX_VUS   - Safety cap on virtual users (default: 10000)

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// ---------------------------------------------------------------------------
// Custom metrics
// ---------------------------------------------------------------------------
const errorRate = new Rate('errors');
const pingLatency = new Trend('ping_latency', true);

// ---------------------------------------------------------------------------
// Options: ramp-up scenario with spike
// ---------------------------------------------------------------------------
export const options = {
  scenarios: {
    ramp_up: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '2m', target: 5000 },   // ramp to 5k trucks
        { duration: '5m', target: 5000 },   // hold steady state
        { duration: '1m', target: 10000 },  // spike to 10k
        { duration: '3m', target: 10000 },  // hold at peak
        { duration: '2m', target: 0 },      // graceful ramp down
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<100', 'p(99)<250'],
    errors: ['rate<0.001'],
    ping_latency: ['p(95)<100'],
  },
};

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8090';

const CORRIDORS = [
  'I-10', 'I-20', 'I-40', 'I-70', 'I-80',
  'I-90', 'I-95', 'I-5',  'I-15', 'I-35',
  'I-55', 'I-65', 'I-75', 'I-85', 'I-4',
  'I-25', 'I-29', 'I-44', 'I-64', 'I-77',
];

// Approximate corridor bounding boxes for realistic coordinate generation.
// Each entry: [latMin, latMax, lonMin, lonMax]
const CORRIDOR_BOUNDS = {
  'I-10': [30.0, 34.5, -118.5, -81.5],
  'I-20': [32.5, 34.0, -97.0, -86.5],
  'I-40': [34.0, 36.5, -118.5, -80.5],
  'I-70': [39.5, 40.5, -105.5, -83.0],
  'I-80': [37.5, 41.0, -122.5, -74.0],
  'I-90': [42.0, 48.0, -122.5, -71.0],
  'I-95': [25.5, 42.5, -80.5, -71.0],
  'I-5':  [32.5, 48.0, -122.5, -117.0],
  'I-15': [32.5, 49.0, -117.5, -114.0],
  'I-35': [29.0, 45.0, -98.5, -93.0],
  'I-55': [29.5, 42.0, -90.5, -87.5],
  'I-65': [30.5, 42.0, -88.5, -87.5],
  'I-75': [25.5, 42.5, -84.5, -80.0],
  'I-85': [33.5, 36.5, -85.0, -79.5],
  'I-4':  [27.9, 28.6, -82.5, -81.3],
  'I-25': [31.5, 40.5, -107.0, -105.0],
  'I-29': [39.0, 44.0, -97.0, -94.5],
  'I-44': [36.0, 39.0, -96.5, -90.0],
  'I-64': [36.5, 39.0, -90.5, -76.0],
  'I-77': [33.5, 41.5, -84.5, -81.5],
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
function randomInRange(min, max) {
  return min + Math.random() * (max - min);
}

// ---------------------------------------------------------------------------
// Default function: one iteration per VU per cycle
// ---------------------------------------------------------------------------
export default function () {
  const truckId = `truck-${__VU}`;
  const corridor = CORRIDORS[__VU % CORRIDORS.length];
  const bounds = CORRIDOR_BOUNDS[corridor];

  // Generate position within corridor bounding box
  const lat = randomInRange(bounds[0], bounds[1]);
  const lon = randomInRange(bounds[2], bounds[3]);
  const speed = Math.round(Math.random() * 120 * 10) / 10;  // 0-120 km/h, 1 decimal
  const heading = Math.floor(Math.random() * 360);

  const payload = JSON.stringify({
    truckId: truckId,
    lat: lat,
    lon: lon,
    speedKmh: speed,
    heading: heading,
    corridorId: corridor,
    timestamp: new Date().toISOString(),
  });

  const res = http.post(`${BASE_URL}/telemetry/ping`, payload, {
    headers: {
      'Content-Type': 'application/json',
      'X-Request-Id': `k6-${__VU}-${__ITER}`,
    },
    tags: { endpoint: 'telemetry_ping' },
  });

  pingLatency.add(res.timings.duration);

  const ok = check(res, {
    'status is 202': (r) => r.status === 202,
    'response time < 100ms': (r) => r.timings.duration < 100,
  });
  errorRate.add(!ok);

  // ~30s real GPS interval compressed to 30ms for load amplification.
  // At 10k VUs this produces ~333k requests/min sustained.
  sleep(0.03);
}

// ---------------------------------------------------------------------------
// Lifecycle hooks
// ---------------------------------------------------------------------------
export function handleSummary(data) {
  const p95 = data.metrics.http_req_duration
    ? data.metrics.http_req_duration.values['p(95)'].toFixed(1)
    : 'N/A';
  const p99 = data.metrics.http_req_duration
    ? data.metrics.http_req_duration.values['p(99)'].toFixed(1)
    : 'N/A';
  const errPct = data.metrics.errors
    ? (data.metrics.errors.values.rate * 100).toFixed(3)
    : 'N/A';

  console.log(`\n${'='.repeat(50)}`);
  console.log('FLEET TELEMETRY LOAD TEST SUMMARY');
  console.log(`${'='.repeat(50)}`);
  console.log(`  p95 latency:  ${p95} ms`);
  console.log(`  p99 latency:  ${p99} ms`);
  console.log(`  error rate:   ${errPct}%`);
  console.log(`${'='.repeat(50)}\n`);

  return {
    stdout: JSON.stringify(data, null, 2),
  };
}
