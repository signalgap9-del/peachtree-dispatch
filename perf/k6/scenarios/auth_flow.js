// Authentication flow load profile.
//
// Models login (Cognito token exchange) followed by authenticated reads
// of saved routes. This exercises the Cognito integration, JWT
// validation in the API, and the DynamoDB saved-route access pattern.
//
// NOTE: This scenario requires valid test credentials. Set AUTH_USERNAME
// and AUTH_PASSWORD env vars, or the login step will be skipped and the
// scenario falls back to unauthenticated reads only.
//
// Usage:
//   k6 run perf/k6/scenarios/auth_flow.js \
//     -e BASE_URL=https://staging.freightscaler.com \
//     -e AUTH_USERNAME=test@freightscaler.com \
//     -e AUTH_PASSWORD=<password>

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { BASE_URL, MAX_VUS, DURATION, tagged } from '../config.js';

export const options = {
  scenarios: {
    auth_flow: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '20s', target: Math.min(5, MAX_VUS) },
        { duration: DURATION, target: Math.min(15, MAX_VUS) },
        { duration: '20s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    'http_req_duration{endpoint:cached_read}': ['p(95)<500'],
    'http_req_duration{endpoint:auth}': ['p(95)<2000'],
    'http_req_failed': ['rate<0.01'],
  },
};

const AUTH_USERNAME = __ENV.AUTH_USERNAME || '';
const AUTH_PASSWORD = __ENV.AUTH_PASSWORD || '';
const COGNITO_TOKEN_URL = __ENV.COGNITO_TOKEN_URL || '';

export default function () {
  let token = '';

  if (AUTH_USERNAME && AUTH_PASSWORD && COGNITO_TOKEN_URL) {
    group('login', function () {
      const res = http.post(
        COGNITO_TOKEN_URL,
        JSON.stringify({
          grant_type: 'password',
          username: AUTH_USERNAME,
          password: AUTH_PASSWORD,
        }),
        Object.assign({ headers: { 'Content-Type': 'application/json' } }, tagged('auth')),
      );
      check(res, { 'login status 200': (r) => r.status === 200 });
      if (res.status === 200) {
        try { token = JSON.parse(res.body).id_token || ''; } catch { /* noop */ }
      }
    });
  }

  group('saved_routes', function () {
    const headers = { 'Content-Type': 'application/json' };
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }
    const res = http.get(`${BASE_URL}/api/routes/saved`, {
      headers,
      tags: { endpoint: 'cached_read' },
    });
    check(res, {
      'saved routes reachable': (r) => r.status === 200 || r.status === 401,
    });
  });

  group('health_check', function () {
    const res = http.get(`${BASE_URL}/api/health`, tagged('cached_read'));
    check(res, { 'health status 200': (r) => r.status === 200 });
  });

  // Authenticated users browse for 5-15s between actions.
  sleep(Math.random() * 10 + 5);
}
