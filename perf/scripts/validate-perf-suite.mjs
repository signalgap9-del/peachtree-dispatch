import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");

const requiredFiles = [
  "perf/README.md",
  "perf/package.json",
  "perf/datasets/places.json",
  "perf/datasets/routes.json",
  "perf/datasets/vrp-scenarios.json",
  "perf/k6/lib/config.js",
  "perf/k6/lib/http.js",
  "perf/k6/lib/data.js",
  "perf/k6/lib/summary.js",
  "perf/k6/scenarios/smoke.js",
  "perf/k6/scenarios/api-read-mix.js",
  "perf/k6/scenarios/route-planning.js",
  "perf/k6/scenarios/graphql-route-engine.js",
  "perf/k6/scenarios/saved-routes.js",
  "docs/architecture/performance-load-testing.md"
];

const scenarioFiles = requiredFiles.filter((file) => file.startsWith("perf/k6/scenarios/"));
const errors = [];

for (const file of requiredFiles) {
  if (!fs.existsSync(path.join(root, file))) {
    errors.push(`Missing required file: ${file}`);
  }
}

validateJsonArray("perf/datasets/places.json", 5);
validateJsonArray("perf/datasets/routes.json", 3);
validateJsonArray("perf/datasets/vrp-scenarios.json", 1);

const config = read("perf/k6/lib/config.js");
mustContain(config, "http://localhost:8080/api/v1", "config default TARGET_URL must stay localhost");
mustContain(config, "ALLOW_REMOTE_TARGET", "remote target guard is required");
mustContain(config, "CONFIRM_STAGING_LOAD", "staging confirmation guard is required");
mustContain(config, "thresholds", "profile thresholds are required");

for (const file of scenarioFiles) {
  const source = read(file);
  mustContain(source, "export const options", `${file} must export k6 options`);
  mustContain(source, "assertSafeLoadTarget", `${file} must call the remote-target guard`);
  mustContain(source, "handleSummary", `${file} must write a summary`);
  mustContain(source, "summaryOutputs", `${file} must use shared summary output`);
}

const savedRoutes = read("perf/k6/scenarios/saved-routes.js");
mustContain(savedRoutes, "requireAuthToken", "saved-route scenario must require a bearer token");
mustContain(savedRoutes, "DELETE", "saved-route scenario must clean up created records");

const readme = read("perf/README.md");
mustContain(readme, "ALLOW_REMOTE_TARGET=true", "README must document the remote target guard");
mustContain(readme, "k6 run", "README must include k6 commands");

if (errors.length) {
  console.error("Performance suite validation failed:");
  for (const error of errors) {
    console.error(`- ${error}`);
  }
  process.exit(1);
}

console.log("Performance suite validation passed.");

function validateJsonArray(file, minLength) {
  const value = JSON.parse(read(file));
  if (!Array.isArray(value)) {
    errors.push(`${file} must contain a JSON array`);
    return;
  }
  if (value.length < minLength) {
    errors.push(`${file} must contain at least ${minLength} records`);
  }
}

function read(file) {
  const absolute = path.join(root, file);
  if (!fs.existsSync(absolute)) {
    return "";
  }
  return fs.readFileSync(absolute, "utf8");
}

function mustContain(source, needle, message) {
  if (!source.includes(needle)) {
    errors.push(message);
  }
}
