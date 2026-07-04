export function summaryOutputs(name, data) {
  const compact = {
    scenario: name,
    profile: __ENV.PERF_PROFILE || "local",
    targetUrl: __ENV.TARGET_URL || "http://localhost:8080/api/v1",
    startedAt: new Date().toISOString(),
    metrics: selectMetrics(data.metrics)
  };

  return {
    stdout: `${JSON.stringify(compact, null, 2)}\n`,
    [`perf/results/${name}-summary.json`]: JSON.stringify(data, null, 2)
  };
}

function selectMetrics(metrics) {
  const names = [
    "http_reqs",
    "http_req_failed",
    "http_req_duration",
    "checks",
    "atmospath_api_contract_pass",
    "atmospath_route_engine_duration",
    "atmospath_saved_route_duration"
  ];
  return Object.fromEntries(names.filter((name) => metrics[name]).map((name) => [name, metrics[name]]));
}
