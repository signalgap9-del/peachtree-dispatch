import { fail } from "k6";

const LOCAL_HOSTS = new Set(["localhost", "127.0.0.1", "::1"]);
const REMOTE_CONFIRMATION_PROFILES = new Set(["staging", "prod", "production"]);

export const PERF_PROFILE = String(__ENV.PERF_PROFILE || "local").toLowerCase();
export const TARGET_URL = normalizeBaseUrl(__ENV.TARGET_URL || "http://localhost:8080/api/v1");
export const RISK_ENGINE_URL = normalizeBaseUrl(__ENV.RISK_ENGINE_URL || "http://localhost:8000");
export const PLATFORM_URL = normalizeBaseUrl(__ENV.PLATFORM_URL || TARGET_URL);
export const AUTH_TOKEN = __ENV.AUTH_TOKEN || "";

const PROFILES = {
  local: {
    smoke: { vus: 1, duration: "30s" },
    readMix: { startVus: 1, stages: [{ duration: "30s", target: 3 }, { duration: "1m", target: 3 }, { duration: "20s", target: 0 }] },
    routePlanning: { rate: 1, duration: "1m", preAllocatedVUs: 2, maxVUs: 6 },
    graphql: { rate: 1, duration: "1m", preAllocatedVUs: 2, maxVUs: 6 },
    savedRoutes: { vus: 1, duration: "45s" },
    thresholds: {
      http_req_failed: ["rate<0.02"],
      checks: ["rate>0.97"],
      "http_req_duration{endpoint:health}": ["p(95)<300"],
      "http_req_duration{endpoint:read}": ["p(95)<1200"],
      "http_req_duration{endpoint:route}": ["p(95)<3500"],
      "http_req_duration{endpoint:graphql}": ["p(95)<3500"],
      "http_req_duration{endpoint:saved_route}": ["p(95)<1800"]
    }
  },
  ci: {
    smoke: { vus: 1, duration: "20s" },
    readMix: { startVus: 1, stages: [{ duration: "20s", target: 2 }, { duration: "20s", target: 0 }] },
    routePlanning: { rate: 1, duration: "30s", preAllocatedVUs: 1, maxVUs: 4 },
    graphql: { rate: 1, duration: "30s", preAllocatedVUs: 1, maxVUs: 4 },
    savedRoutes: { vus: 1, duration: "20s" },
    thresholds: {
      http_req_failed: ["rate<0.03"],
      checks: ["rate>0.95"],
      "http_req_duration{endpoint:health}": ["p(95)<500"],
      "http_req_duration{endpoint:read}": ["p(95)<1500"],
      "http_req_duration{endpoint:route}": ["p(95)<4500"],
      "http_req_duration{endpoint:graphql}": ["p(95)<4500"],
      "http_req_duration{endpoint:saved_route}": ["p(95)<2200"]
    }
  },
  staging: {
    smoke: { vus: 2, duration: "1m" },
    readMix: { startVus: 2, stages: [{ duration: "1m", target: 10 }, { duration: "3m", target: 10 }, { duration: "1m", target: 0 }] },
    routePlanning: { rate: 2, duration: "3m", preAllocatedVUs: 4, maxVUs: 16 },
    graphql: { rate: 2, duration: "3m", preAllocatedVUs: 4, maxVUs: 16 },
    savedRoutes: { vus: 3, duration: "2m" },
    thresholds: {
      http_req_failed: ["rate<0.01"],
      checks: ["rate>0.98"],
      "http_req_duration{endpoint:health}": ["p(95)<400"],
      "http_req_duration{endpoint:read}": ["p(95)<1000", "p(99)<2200"],
      "http_req_duration{endpoint:route}": ["p(95)<3000", "p(99)<7000"],
      "http_req_duration{endpoint:graphql}": ["p(95)<3200", "p(99)<7500"],
      "http_req_duration{endpoint:saved_route}": ["p(95)<1600", "p(99)<3200"]
    }
  }
};

export function profile() {
  return PROFILES[PERF_PROFILE] || PROFILES.local;
}

export function scenarioProfile(name) {
  return profile()[name] || PROFILES.local[name];
}

export function buildOptions(scenarioName, scenarioBuilder, extraThresholds = {}) {
  return {
    discardResponseBodies: false,
    noConnectionReuse: false,
    userAgent: `AtmosPathPerf/${scenarioName}`,
    summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
    scenarios: scenarioBuilder(scenarioProfile(scenarioName)),
    thresholds: {
      ...profile().thresholds,
      ...extraThresholds
    }
  };
}

export function assertSafeLoadTarget() {
  const targets = [
    ["TARGET_URL", TARGET_URL],
    ["RISK_ENGINE_URL", RISK_ENGINE_URL],
    ["PLATFORM_URL", PLATFORM_URL]
  ];
  const remoteTargets = targets.filter(([, value]) => !isLocalUrl(value));

  if (remoteTargets.length > 0 && __ENV.ALLOW_REMOTE_TARGET !== "true") {
    fail(
      `Remote load target blocked: ${remoteTargets.map(([name, value]) => `${name}=${value}`).join(", ")}. ` +
        "Set ALLOW_REMOTE_TARGET=true only after confirming budgets, throttles, and alarms."
    );
  }

  if (REMOTE_CONFIRMATION_PROFILES.has(PERF_PROFILE) && __ENV.CONFIRM_STAGING_LOAD !== "true") {
    fail(`PERF_PROFILE=${PERF_PROFILE} requires CONFIRM_STAGING_LOAD=true.`);
  }

  if ((PERF_PROFILE === "prod" || PERF_PROFILE === "production") && __ENV.CONFIRM_PROD_LOAD !== "true") {
    fail("Production load tests require CONFIRM_PROD_LOAD=true and an approved release window.");
  }
}

export function requireAuthToken() {
  if (!AUTH_TOKEN) {
    fail("AUTH_TOKEN is required for authenticated saved-route performance scenarios.");
  }
}

export function endpoint(baseUrl, path) {
  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  return `${normalizeBaseUrl(baseUrl)}${normalizedPath}`;
}

export function jsonParams(endpointName, extraHeaders = {}) {
  return {
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      ...extraHeaders
    },
    tags: {
      endpoint: endpointName
    }
  };
}

export function authJsonParams(endpointName, extraHeaders = {}) {
  return jsonParams(endpointName, {
    Authorization: `Bearer ${AUTH_TOKEN}`,
    ...extraHeaders
  });
}

function normalizeBaseUrl(value) {
  return String(value).replace(/\/+$/, "");
}

function isLocalUrl(value) {
  try {
    const url = new URL(value);
    return LOCAL_HOSTS.has(url.hostname);
  } catch {
    return false;
  }
}
