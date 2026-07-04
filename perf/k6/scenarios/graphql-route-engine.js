import { sleep } from "k6";
import { assertSafeLoadTarget, buildOptions, jsonParams, TARGET_URL } from "../lib/config.js";
import {
  deterministicItem,
  graphqlCapabilitiesQuery,
  graphqlPlanRouteMutation
} from "../lib/data.js";
import { postJson, recordRouteDuration } from "../lib/http.js";
import { summaryOutputs } from "../lib/summary.js";

const places = JSON.parse(open("../../datasets/places.json"));
const routes = JSON.parse(open("../../datasets/routes.json"));

export const options = buildOptions("graphql", (profile) => ({
  graphql_gateway: {
    executor: "constant-arrival-rate",
    rate: profile.rate,
    timeUnit: "1s",
    duration: profile.duration,
    preAllocatedVUs: profile.preAllocatedVUs,
    maxVUs: profile.maxVUs
  }
}), {
  "http_req_failed{endpoint:graphql}": ["rate<0.02"],
  atmospath_route_engine_duration: ["p(95)<3500", "p(99)<8000"]
});

export function setup() {
  assertSafeLoadTarget();
}

export default function () {
  const route = deterministicItem(routes);
  const params = jsonParams("graphql");

  const capabilities = postJson(
    TARGET_URL,
    "/graphql",
    graphqlCapabilitiesQuery(),
    "graphql",
    params,
    { label: "graphql capabilities", field: "data" }
  );
  recordRouteDuration(capabilities);

  const plan = postJson(
    TARGET_URL,
    "/graphql",
    graphqlPlanRouteMutation(route, places),
    "graphql",
    params,
    { label: "graphql route plan", field: "data" }
  );
  recordRouteDuration(plan);

  sleep(1);
}

export function handleSummary(data) {
  return summaryOutputs("graphql-route-engine", data);
}
