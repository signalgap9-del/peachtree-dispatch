"""Synthetic canary for AtmosPath.

Invoked every 5 minutes by EventBridge.  Calls the deployed /health endpoint
and a representative route-planning flow, then publishes success/failure and
latency as CloudWatch custom metrics.
"""
import json
import os
import time
import urllib.request

import boto3

API_BASE_URL = os.environ.get("API_BASE_URL", "")
NAMESPACE = "AtmosPath/Canary"
cloudwatch = boto3.client("cloudwatch", region_name=os.environ.get("AWS_REGION", "us-east-1"))

CHECKS = [
    ("health", "/health"),
    ("risk_national", "/risk/national"),
]


def handler(event, context):
    results = []
    for name, path in CHECKS:
        url = f"{API_BASE_URL}{path}"
        start = time.monotonic()
        try:
            req = urllib.request.Request(url, headers={"Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=15) as resp:
                body = json.loads(resp.read())
            latency_ms = (time.monotonic() - start) * 1000
            success = resp.status == 200
            if name == "health" and success:
                stale = body.get("stale_providers", [])
                if stale:
                    success = False
                    print(f"stale providers: {stale}")
        except Exception as exc:
            latency_ms = (time.monotonic() - start) * 1000
            success = False
            print(f"canary check {name} failed: {exc}")

        results.append({"name": name, "success": success, "latency_ms": round(latency_ms, 1)})
        _put_metrics(name, success, latency_ms)

    all_ok = all(r["success"] for r in results)
    _put_metrics("overall", all_ok, 0)
    print(json.dumps({"canary": results, "overall_success": all_ok}))
    return {"statusCode": 200 if all_ok else 503, "body": json.dumps(results)}


def _put_metrics(check_name, success, latency_ms):
    metrics = [
        {
            "MetricName": "Success",
            "Dimensions": [{"Name": "Check", "Value": check_name}],
            "Value": 1.0 if success else 0.0,
            "Unit": "None",
        },
    ]
    if latency_ms > 0:
        metrics.append(
            {
                "MetricName": "Latency",
                "Dimensions": [{"Name": "Check", "Value": check_name}],
                "Value": latency_ms,
                "Unit": "Milliseconds",
            }
        )
    try:
        cloudwatch.put_metric_data(Namespace=NAMESPACE, MetricData=metrics)
    except Exception as exc:
        print(f"failed to publish metrics for {check_name}: {exc}")
