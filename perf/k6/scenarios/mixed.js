// Mixed realistic load profile.
//
// Blends all traffic classes into a single ramping test that approximates
// a typical weekday usage pattern. Traffic split:
//   - 50% route planning and place search (core dispatcher workflow)
//   - 25% cached reads (national risk, health)
//   - 15% authenticated saved-route reads
//   - 10% AI chat (capped at 3 concurrent VUs for cost control)
//
// Usage:
//   k6 run perf/k6/scenarios/mixed.js -e BASE_URL=https://staging.freightscaler.com

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, MAX_VUS, PLACES, THRESHOLDS, tagged } from '../config.js';

export const options = {
  scenarios: {
    // Core dispatcher traffic: route planning + place search + risk reads.
    dispatcher: {
      executor: 'ramping-vus',
      exec: 'dispatcherFlow',
      startVUs: 0,
      stages: [
        { duration: '30s', target: Math.min(10, MAX_VUS) },   // warm up
        { duration: '1m', target: Math.min(25, MAX_VUS) },    // ramp to peak
        { duration: '2m', target: Math.min(25, MAX_VUS) },    // sustain peak
        { duration: '30s', target: Math.min(5, MAX_VUS) },    // cool down
        { duration: '30s', target: 0 },                        // ramp out
      ],
      gracefulRampDown: '15s',
    },
    // AI chat: low volume, high cost per request.
    ai_assistant: {
      executor: 'ramping-vus',
      exec: 'aiChatFlow',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 1 },
        { duration: '2m', target: Math.min(3, MAX_VUS) },
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
  [PLACES.atlanta, PLACES.chicago],
];

const cities = ['Miami', 'Dallas', 'Chicago', 'Atlanta', 'Seattle', 'Denver'];

const prompts = [
  'What weather risks should I expect on the I-95 corridor this week?',
  'Summarize the safest route from Atlanta to Miami for a hazmat load.',
  'Are there any active flood warnings along I-10 through Texas?',
];

export function dispatcherFlow() {
  const roll = Math.random();

  if (roll < 0.4) {
    // Route planning
    const [origin, dest] = corridors[Math.floor(Math.random() * corridors.length)];
    const vehicleTypes = ['CAR', 'TRUCK', 'VAN'];
    const vt = vehicleTypes[Math.floor(Math.random() * vehicleTypes.length)];
    const res = http.post(
      `${BASE_URL}/api/directions`,
      JSON.stringify({ origin, destination: dest, vehicle_type: vt }),
      Object.assign({ headers: { 'Content-Type': 'application/json' } }, tagged('route_planning')),
    );
    check(res, { 'directions 200': (r) => r.status === 200 });
  } else if (roll < 0.65) {
    // Place search
    const q = cities[Math.floor(Math.random() * cities.length)];
    const res = http.get(`${BASE_URL}/api/places/search?q=${q}`, tagged('cached_read'));
    check(res, { 'place search 200': (r) => r.status === 200 });
  } else if (roll < 0.85) {
    // National risk (cached)
    const res = http.get(`${BASE_URL}/api/risk/national`, tagged('cached_read'));
    check(res, { 'risk national 200': (r) => r.status === 200 });
  } else {
    // Saved routes (unauthenticated probe; real auth is in auth_flow.js)
    const res = http.get(`${BASE_URL}/api/routes/saved`, tagged('cached_read'));
    check(res, { 'saved routes reachable': (r) => r.status === 200 || r.status === 401 });
  }

  sleep(Math.random() * 15 + 5);
}

export function aiChatFlow() {
  const prompt = prompts[Math.floor(Math.random() * prompts.length)];
  const res = http.post(
    `${BASE_URL}/api/chat`,
    JSON.stringify({ message: prompt }),
    Object.assign({ headers: { 'Content-Type': 'application/json' } }, tagged('ai_chat')),
  );
  check(res, { 'chat reachable': (r) => r.status === 200 || r.status === 501 });

  // Long think time for AI responses.
  sleep(Math.random() * 30 + 30);
}
