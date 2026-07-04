import http from "k6/http";
import { sleep } from "k6";
import {
  assertSafeLoadTarget,
  authJsonParams,
  buildOptions,
  PLATFORM_URL,
  requireAuthToken
} from "../lib/config.js";
import { deterministicItem, routeFixtureToSavedRoutePayload } from "../lib/data.js";
import { expectStatus, parseJson, postJson, recordSavedRouteDuration } from "../lib/http.js";
import { summaryOutputs } from "../lib/summary.js";

const places = JSON.parse(open("../../datasets/places.json"));
const routes = JSON.parse(open("../../datasets/routes.json"));

export const options = buildOptions("savedRoutes", (profile) => ({
  saved_routes: {
    executor: "constant-vus",
    vus: profile.vus,
    duration: profile.duration
  }
}), {
  atmospath_saved_route_duration: ["p(95)<1800", "p(99)<3500"]
});

export function setup() {
  assertSafeLoadTarget();
  requireAuthToken();
}

export default function () {
  const route = deterministicItem(routes);
  const params = authJsonParams("saved_route");

  const created = postJson(
    PLATFORM_URL,
    "/me/saved/routes",
    routeFixtureToSavedRoutePayload(route, places),
    "saved_route",
    params,
    { status: 201, label: "create saved route", field: "savedItemId" }
  );
  recordSavedRouteDuration(created);

  const body = parseJson(created, {});
  if (!body.savedItemId) {
    sleep(1);
    return;
  }

  const currentRisk = postOrGet("GET", `/me/saved/routes/${body.savedItemId}/current-risk`, null, params);
  expectStatus(currentRisk, 200, "saved route current risk status 200");
  recordSavedRouteDuration(currentRisk);

  const history = postOrGet("GET", `/me/saved/routes/${body.savedItemId}/risk-history`, null, params);
  expectStatus(history, 200, "saved route risk history status 200");
  recordSavedRouteDuration(history);

  const deleted = postOrGet("DELETE", `/me/saved/routes/${body.savedItemId}`, null, params);
  expectStatus(deleted, 204, "delete saved route status 204");
  recordSavedRouteDuration(deleted);

  sleep(1);
}

export function handleSummary(data) {
  return summaryOutputs("saved-routes", data);
}

function postOrGet(method, path, body, params) {
  const url = `${PLATFORM_URL}${path}`;
  if (method === "GET") {
    return httpRequest("GET", url, null, params);
  }
  if (method === "DELETE") {
    return httpRequest("DELETE", url, null, params);
  }
  return httpRequest("POST", url, JSON.stringify(body), params);
}

function httpRequest(method, url, body, params) {
  return http.request(method, url, body, params);
}
