import { sleep } from "k6";
import { assertSafeLoadTarget, buildOptions, RISK_ENGINE_URL, TARGET_URL } from "../lib/config.js";
import { getJson } from "../lib/http.js";
import { summaryOutputs } from "../lib/summary.js";

export const options = buildOptions("smoke", (profile) => ({
  smoke: {
    executor: "constant-vus",
    vus: profile.vus,
    duration: profile.duration
  }
}));

export function setup() {
  assertSafeLoadTarget();
}

export default function () {
  getJson(TARGET_URL, "/health", "health", { label: "platform health", field: "status" });
  getJson(RISK_ENGINE_URL, "/health", "health", { label: "risk health", field: "status" });
  getJson(TARGET_URL, "/risk/national", "read", { label: "national risk", field: "active_alerts" });
  getJson(TARGET_URL, "/road-events/feeds?limit=10", "read", { label: "road event feeds", field: "feeds" });
  sleep(1);
}

export function handleSummary(data) {
  return summaryOutputs("smoke", data);
}
