#!/usr/bin/env python3
"""Cost-free local stress harness for the AtmosPath internal risk engine.

This intentionally uses FastAPI's in-process TestClient so the default run does
not hit AWS, Google APIs, NOAA endpoints, or any paid infrastructure. It is a
repeatable release gate for endpoint latency regressions before staging load
tests are considered.
"""

from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Callable


ROOT = Path(__file__).resolve().parents[1]
API_ROOT = ROOT / "services" / "api"
sys.path.insert(0, str(API_ROOT))
os.environ.setdefault("DATABASE_PATH", str(Path(tempfile.gettempdir()) / "atmospath-local-stress.db"))

from fastapi.testclient import TestClient  # noqa: E402

from app.main import app  # noqa: E402


SEATTLE = {
    "place_id": "stress-seattle",
    "display_name": "Seattle, Washington, United States",
    "city": "Seattle",
    "state": "Washington",
    "latitude": 47.6062,
    "longitude": -122.3321,
}

MIAMI = {
    "place_id": "stress-miami",
    "display_name": "Miami, Florida, United States",
    "city": "Miami",
    "state": "Florida",
    "latitude": 25.7617,
    "longitude": -80.1918,
}

ATLANTA = {
    "place_id": "stress-atlanta",
    "display_name": "Atlanta, Georgia, United States",
    "city": "Atlanta",
    "state": "Georgia",
    "latitude": 33.749,
    "longitude": -84.388,
}

_thread_local = threading.local()


@dataclass(frozen=True)
class RequestResult:
    endpoint: str
    status_code: int
    ok: bool
    latency_ms: float


@dataclass(frozen=True)
class EndpointSummary:
    endpoint: str
    count: int
    failures: int
    p50_ms: float
    p95_ms: float
    p99_ms: float
    max_ms: float


@dataclass(frozen=True)
class StressSummary:
    requests: int
    concurrency: int
    failures: int
    duration_seconds: float
    requests_per_second: float
    p50_ms: float
    p95_ms: float
    p99_ms: float
    endpoints: list[EndpointSummary]


def client() -> TestClient:
    existing = getattr(_thread_local, "client", None)
    if existing is None:
        existing = TestClient(app)
        _thread_local.client = existing
    return existing


def scenarios() -> list[tuple[str, Callable[[], Any]]]:
    return [
        ("GET /health", lambda: client().get("/health")),
        ("GET /risk/national", lambda: client().get("/risk/national")),
        ("GET /places/search", lambda: client().get("/places/search?q=Miami")),
        ("POST /risk/location", lambda: client().post("/risk/location", json=MIAMI)),
        (
            "POST /directions",
            lambda: client().post(
                "/directions",
                json={"origin": SEATTLE, "destination": MIAMI, "vehicle_type": "CAR"},
            ),
        ),
        (
            "POST /routes/multi-stop",
            lambda: client().post(
                "/routes/multi-stop",
                json={
                    "mode": "MANUAL_ORDER",
                    "vehicleType": "VAN",
                    "stops": [
                        {"stopId": "A", "kind": "DEPOT", "name": "Atlanta", "latitude": ATLANTA["latitude"], "longitude": ATLANTA["longitude"]},
                        {"stopId": "B", "kind": "FINAL", "name": "Miami", "latitude": MIAMI["latitude"], "longitude": MIAMI["longitude"]},
                    ],
                },
            ),
        ),
    ]


def run_one(index: int) -> RequestResult:
    items = scenarios()
    endpoint, invoke = items[index % len(items)]
    start = time.perf_counter()
    try:
        response = invoke()
        latency_ms = (time.perf_counter() - start) * 1000
        return RequestResult(endpoint, response.status_code, response.status_code < 500, latency_ms)
    except Exception:
        latency_ms = (time.perf_counter() - start) * 1000
        return RequestResult(endpoint, 0, False, latency_ms)


def percentile(values: list[float], percentile_value: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, round((percentile_value / 100) * (len(ordered) - 1))))
    return ordered[index]


def summarize(results: list[RequestResult], concurrency: int, duration_seconds: float) -> StressSummary:
    latencies = [result.latency_ms for result in results]
    endpoint_summaries: list[EndpointSummary] = []
    for endpoint in sorted({result.endpoint for result in results}):
        group = [result for result in results if result.endpoint == endpoint]
        group_latencies = [result.latency_ms for result in group]
        endpoint_summaries.append(
            EndpointSummary(
                endpoint=endpoint,
                count=len(group),
                failures=sum(1 for result in group if not result.ok),
                p50_ms=round(statistics.median(group_latencies), 2),
                p95_ms=round(percentile(group_latencies, 95), 2),
                p99_ms=round(percentile(group_latencies, 99), 2),
                max_ms=round(max(group_latencies), 2),
            )
        )
    return StressSummary(
        requests=len(results),
        concurrency=concurrency,
        failures=sum(1 for result in results if not result.ok),
        duration_seconds=round(duration_seconds, 2),
        requests_per_second=round(len(results) / duration_seconds, 2) if duration_seconds else 0,
        p50_ms=round(statistics.median(latencies), 2),
        p95_ms=round(percentile(latencies, 95), 2),
        p99_ms=round(percentile(latencies, 99), 2),
        endpoints=endpoint_summaries,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Run local AtmosPath API stress smoke without cloud cost.")
    parser.add_argument("--requests", type=int, default=180)
    parser.add_argument("--concurrency", type=int, default=8)
    parser.add_argument("--warmup-rounds", type=int, default=1)
    parser.add_argument("--json-output", type=Path)
    args = parser.parse_args()

    if args.requests <= 0 or args.concurrency <= 0:
        parser.error("--requests and --concurrency must be positive")

    for _ in range(args.warmup_rounds):
        for index in range(len(scenarios())):
            run_one(index)

    start = time.perf_counter()
    results: list[RequestResult] = []
    with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = [executor.submit(run_one, index) for index in range(args.requests)]
        for future in as_completed(futures):
            results.append(future.result())
    duration_seconds = time.perf_counter() - start
    summary = summarize(results, args.concurrency, duration_seconds)

    print(f"AtmosPath local API stress: {summary.requests} requests, concurrency {summary.concurrency}")
    print(f"Failures: {summary.failures}; throughput: {summary.requests_per_second} req/s; p95: {summary.p95_ms} ms")
    for endpoint in summary.endpoints:
        print(
            f"- {endpoint.endpoint}: count={endpoint.count} failures={endpoint.failures} "
            f"p50={endpoint.p50_ms}ms p95={endpoint.p95_ms}ms p99={endpoint.p99_ms}ms max={endpoint.max_ms}ms"
        )

    if args.json_output:
        args.json_output.parent.mkdir(parents=True, exist_ok=True)
        args.json_output.write_text(json.dumps(asdict(summary), indent=2), encoding="utf-8")
        print(f"Wrote JSON summary to {args.json_output}")

    return 0 if summary.failures == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
