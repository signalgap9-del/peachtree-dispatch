import { sleep } from "k6";
import { assertSafeLoadTarget, buildOptions, TARGET_URL } from "../lib/config.js";
import { deterministicItem } from "../lib/data.js";
import { getJson } from "../lib/http.js";
import { summaryOutputs } from "../lib/summary.js";

const places = JSON.parse(open("../../datasets/places.json"));
const searchTerms = ["Atlanta", "Miami", "Seattle", "I-35", "flood", "heat"];
const states = ["GA", "FL", "TX", "WA", "IL"];

export const options = buildOptions("readMix", (profile) => ({
  read_mix: {
    executor: "ramping-vus",
    startVUs: profile.startVus,
    stages: profile.stages,
    gracefulRampDown: "10s"
  }
}));

export function setup() {
  assertSafeLoadTarget();
}

export default function () {
  const place = deterministicItem(places);
  const searchTerm = deterministicItem(searchTerms);
  const state = deterministicItem(states);

  getJson(TARGET_URL, `/places/search?q=${encodeURIComponent(searchTerm)}`, "read", {
    label: "place search"
  });
  getJson(TARGET_URL, "/risk/weather-snapshot", "read", {
    label: "weather snapshot",
    field: "points"
  });
  getJson(TARGET_URL, "/risk/national", "read", {
    label: "national risk",
    field: "alerts"
  });
  getJson(TARGET_URL, `/road-events/feeds?state=${state}&limit=20`, "read", {
    label: "state road events",
    field: "feeds"
  });
  getJson(TARGET_URL, `/places/search?q=${encodeURIComponent(place.city)}`, "read", {
    label: "city lookup"
  });

  sleep(1);
}

export function handleSummary(data) {
  return summaryOutputs("api-read-mix", data);
}
