// Route planning load profile.
//
// Models the core freight workflow: a dispatcher plans a route, searches
// for places, and checks national risk conditions. Route planning
// (POST /directions) is the heaviest call because it fans out to the
// risk engine and external routing APIs.
//
// Usage:
//   k6 run perf/k6/scenarios/route_planning.js -e BASE_URL=https://staging.freightscaler.com

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, MAX_VUS, DURATION, PLACES, THRESHOLDS, tagged } from '../config.js';

export const options = {
  scenarios: {
    route_planning: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: Math.min(10, MAX_VUS) },
        { duration: DURATION, target: Math.min(20, MAX_VUS) },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: THRESHOLDS,
};

const corridors = [
  [PLACES.seattle, PLACES.miami],
  [PLACES.atlanta, PLACES.dallas],
  [PLACES.chicago, PLACES.miami],
  [PLACES.dallas, PLACES.seattle],
];

export default function () {
  const roll = Math.random();

  if (roll < 0.5) {
    // 50% — route planning (the expensive path)
    const [origin, dest] = corridors[Math.floor(Math.random() * corridors.length)];
    const res = http.post(
      `${BASE_URL}/api/directions`,
      JSON.stringify({ origin, destination: dest, vehicle_type: 'TRUCK' }),
      Object.assign({ headers: { 'Content-Type': 'application/json' } }, tagged('route_planning')),
    );
    check(res, {
      'directions status 200': (r) => r.status === 200,
      'directions has routes': (r) => {
        try { return JSON.parse(r.body).routes !== undefined; } catch { return false; }
      },
    });
  } else if (roll < 0.8) {
    // 30% — place search
    const cities = ['Miami', 'Dallas', 'Chicago', 'Atlanta', 'Seattle'];
    const q = cities[Math.floor(Math.random() * cities.length)];
    const res = http.get(`${BASE_URL}/api/places/search?q=${q}`, tagged('cached_read'));
    check(res, { 'place search status 200': (r) => r.status === 200 });
  } else {
    // 20% — national risk read (cached, should be fast)
    const res = http.get(`${BASE_URL}/api/risk/national`, tagged('cached_read'));
    check(res, { 'national risk status 200': (r) => r.status === 200 });
  }

  // Think time: dispatchers spend 10-30s reviewing results.
  sleep(Math.random() * 20 + 10);
}
