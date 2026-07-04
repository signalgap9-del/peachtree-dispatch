import { sleep } from "k6";
import { assertSafeLoadTarget, buildOptions, jsonParams, TARGET_URL } from "../lib/config.js";
import {
  deterministicItem,
  routeFixtureToDirectionsPayload,
  routeFixtureToMultiStopPayload
} from "../lib/data.js";
import { postJson, recordRouteDuration } from "../lib/http.js";
import { summaryOutputs } from "../lib/summary.js";

const places = JSON.parse(open("../../datasets/places.json"));
const routes = JSON.parse(open("../../datasets/routes.json"));

export const options = buildOptions("routePlanning", (profile) => ({
  route_planning: {
    executor: "constant-arrival-rate",
    rate: profile.rate,
    timeUnit: "1s",
    duration: profile.duration,
    preAllocatedVUs: profile.preAllocatedVUs,
    maxVUs: profile.maxVUs
  }
}), {
  atmospath_route_engine_duration: ["p(95)<3500", "p(99)<8000"]
});

export function setup() {
  assertSafeLoadTarget();
}

export default function () {
  const route = deterministicItem(routes);

  const directions = postJson(
    TARGET_URL,
    "/directions",
    routeFixtureToDirectionsPayload(route, places),
    "route",
    jsonParams("route"),
    { label: "directions", field: "alternatives" }
  );
  recordRouteDuration(directions);

  const multiStop = postJson(
    TARGET_URL,
    "/routes/multi-stop",
    routeFixtureToMultiStopPayload(route, places, false),
    "route",
    jsonParams("route"),
    { label: "multi-stop route", field: "legs" }
  );
  recordRouteDuration(multiStop);

  if (__ITER % 3 === 0) {
    const optimized = postJson(
      TARGET_URL,
      "/routes/multi-stop/optimize",
      routeFixtureToMultiStopPayload(route, places, true),
      "route",
      jsonParams("route"),
      { label: "optimized route", field: "legs" }
    );
    recordRouteDuration(optimized);
  }

  sleep(1);
}

export function handleSummary(data) {
  return summaryOutputs("route-planning", data);
}
