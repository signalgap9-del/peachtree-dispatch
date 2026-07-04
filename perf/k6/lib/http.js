import http from "k6/http";
import { check } from "k6";
import { Rate, Trend } from "k6/metrics";
import { endpoint, jsonParams } from "./config.js";

export const apiContractPassRate = new Rate("atmospath_api_contract_pass");
export const routeEngineDuration = new Trend("atmospath_route_engine_duration", true);
export const savedRouteDuration = new Trend("atmospath_saved_route_duration", true);

export function getJson(baseUrl, path, endpointName, expectations = {}) {
  const response = http.get(endpoint(baseUrl, path), jsonParams(endpointName));
  recordContract(response, expectations);
  return response;
}

export function postJson(baseUrl, path, body, endpointName, params = jsonParams(endpointName), expectations = {}) {
  const response = http.post(endpoint(baseUrl, path), JSON.stringify(body), params);
  recordContract(response, expectations);
  return response;
}

export function recordRouteDuration(response) {
  routeEngineDuration.add(response.timings.duration);
}

export function recordSavedRouteDuration(response) {
  savedRouteDuration.add(response.timings.duration);
}

export function parseJson(response, fallback = null) {
  try {
    return response.json();
  } catch {
    return fallback;
  }
}

export function expectStatus(response, expectedStatus, label) {
  const passed = check(response, {
    [label]: (res) => res.status === expectedStatus
  });
  apiContractPassRate.add(passed);
  return passed;
}

export function expectJsonField(response, fieldName, label) {
  const body = parseJson(response);
  const passed = check(body, {
    [label]: (json) => json !== null && Object.prototype.hasOwnProperty.call(json, fieldName)
  });
  apiContractPassRate.add(passed);
  return passed;
}

function recordContract(response, expectations) {
  const expectedStatus = expectations.status || 200;
  expectStatus(response, expectedStatus, `${expectations.label || "request"} status ${expectedStatus}`);

  if (expectations.field) {
    expectJsonField(response, expectations.field, `${expectations.label || "response"} has ${expectations.field}`);
  }
}
