// AI chat load profile.
//
// LLM-backed chat requests are the most expensive per-call path (token
// costs, longer latency). This scenario uses deliberately low volume
// to model realistic adoption without burning through the AI budget.
//
// Cost guard: MAX_VUS defaults to 5 for this scenario. Override with
// -e MAX_VUS=<n> only if you understand the per-request LLM cost.
//
// Usage:
//   k6 run perf/k6/scenarios/ai_chat.js -e BASE_URL=https://staging.freightscaler.com

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, DURATION, tagged } from '../config.js';

// Hard ceiling for AI scenarios — these cost real money per request.
const AI_MAX_VUS = parseInt(__ENV.MAX_VUS || '5', 10);

export const options = {
  scenarios: {
    ai_chat: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '20s', target: Math.min(2, AI_MAX_VUS) },
        { duration: DURATION, target: Math.min(3, AI_MAX_VUS) },
        { duration: '20s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    // AI responses are slower; allow generous latency but still cap errors.
    'http_req_duration{endpoint:ai_chat}': ['p(95)<10000'],
    'http_req_failed': ['rate<0.02'],
  },
};

const prompts = [
  'What weather risks should I expect on the I-95 corridor this week?',
  'Summarize the safest route from Atlanta to Miami for a hazmat load.',
  'Are there any active flood warnings along I-10 through Texas?',
  'Compare the risk profiles for the Seattle to Dallas corridor.',
];

export default function () {
  const prompt = prompts[Math.floor(Math.random() * prompts.length)];
  const res = http.post(
    `${BASE_URL}/api/chat`,
    JSON.stringify({ message: prompt, context: { corridor: 'I-95' } }),
    Object.assign({ headers: { 'Content-Type': 'application/json' } }, tagged('ai_chat')),
  );

  check(res, {
    'chat status 200': (r) => r.status === 200,
    'chat has reply': (r) => {
      try { return JSON.parse(r.body).reply !== undefined; } catch { return false; }
    },
  });

  // Users read AI responses slowly; model 30-60s think time.
  sleep(Math.random() * 30 + 30);
}
