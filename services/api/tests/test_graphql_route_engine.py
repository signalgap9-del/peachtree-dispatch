from app.models import VehicleType
from app.vrp.models import MultiStopRoutePlan, VRPSolution
from fastapi.testclient import TestClient

from app.main import app


client = TestClient(app)


def test_graphql_exposes_route_engine_capabilities() -> None:
    response = client.post(
        "/graphql",
        json={
            "query": """
                query Capabilities {
                  routeEngineCapabilities {
                    supportsGraphql
                    maxMultiStopStops
                    maxVrpJobs
                    supportedSolvers
                    mlShadowMode
                  }
                }
            """,
        },
    )

    assert response.status_code == 200
    payload = response.json()
    assert "errors" not in payload
    assert payload["data"]["routeEngineCapabilities"] == {
        "supportsGraphql": True,
        "maxMultiStopStops": 25,
        "maxVrpJobs": 100,
        "supportedSolvers": ["ortools"],
        "mlShadowMode": "SHADOW_DISABLED",
    }


def test_graphql_plans_multi_stop_route(monkeypatch) -> None:
    monkeypatch.setattr(
        "app.graphql_schema.multi_stop_route_service.plan",
        lambda command: MultiStopRoutePlan(
            mode=command.mode,
            vehicle_type=command.vehicle_type,
            submitted_sequence=[stop.stop_id for stop in command.stops],
            optimized_sequence=None,
            sequence_changed=False,
            total_distance_miles=14.2,
            total_duration_minutes=21.5,
            risk_adjusted_duration_minutes=25.0,
            route_risk_score=31,
            legs=[],
            source_status={"routing_matrix": "LIVE", "ml_cost_model": "DISABLED"},
        ),
    )

    response = client.post(
        "/graphql",
        json={
            "query": """
                mutation Plan($input: MultiStopRouteInput!) {
                  planMultiStopRoute(input: $input) {
                    vehicleType
                    submittedSequence
                    routeRiskScore
                    riskAdjustedDurationMinutes
                    sourceStatus {
                      key
                      value
                    }
                  }
                }
            """,
            "variables": {
                "input": {
                    "mode": "MANUAL_ORDER",
                    "vehicleType": "CAR",
                    "stops": [
                        {"stopId": "A", "kind": "DEPOT", "name": "Atlanta", "latitude": 33.749, "longitude": -84.388},
                        {"stopId": "B", "kind": "FINAL", "name": "Macon", "latitude": 32.8407, "longitude": -83.6324},
                    ],
                },
            },
        },
    )

    assert response.status_code == 200
    payload = response.json()
    assert "errors" not in payload
    plan = payload["data"]["planMultiStopRoute"]
    assert plan["vehicleType"] == "CAR"
    assert plan["submittedSequence"] == ["A", "B"]
    assert plan["routeRiskScore"] == 31
    assert plan["riskAdjustedDurationMinutes"] == 25.0
    assert plan["sourceStatus"] == [
        {"key": "routing_matrix", "value": "LIVE"},
        {"key": "ml_cost_model", "value": "DISABLED"},
    ]


def test_graphql_solves_vrp_scenario(monkeypatch) -> None:
    monkeypatch.setattr(
        "app.graphql_schema.vrp_optimization_service.solve",
        lambda scenario: VRPSolution(
            solver=scenario.solver,
            status="FEASIBLE",
            objective_value=1830,
            solve_time_ms=12,
            routes=[],
            dropped_jobs=["job-2"],
            source_status={"routing_matrix": "LIVE", "ml_cost_model": "SHADOW"},
        ),
    )

    response = client.post(
        "/graphql",
        json={
            "query": """
                mutation Solve($input: VrpScenarioInput!) {
                  solveVrp(input: $input) {
                    solver
                    status
                    objectiveValue
                    solveTimeMs
                    droppedJobs
                    sourceStatus {
                      key
                      value
                    }
                  }
                }
            """,
            "variables": {
                "input": {
                    "depot": {"name": "Atlanta depot", "location": {"latitude": 33.749, "longitude": -84.388}},
                    "vehicles": [
                        {
                            "vehicleId": "van-1",
                            "vehicleType": "VAN",
                            "capacityUnits": 2,
                            "startLocation": {"latitude": 33.749, "longitude": -84.388},
                        }
                    ],
                    "jobs": [
                        {"jobId": "job-1", "name": "Macon", "location": {"latitude": 32.8407, "longitude": -83.6324}},
                        {"jobId": "job-2", "name": "Savannah", "location": {"latitude": 32.0809, "longitude": -81.0912}},
                    ],
                    "costModel": {"useMlShadowCost": True},
                },
            },
        },
    )

    assert response.status_code == 200
    payload = response.json()
    assert "errors" not in payload
    solution = payload["data"]["solveVrp"]
    assert solution["status"] == "FEASIBLE"
    assert solution["droppedJobs"] == ["job-2"]
    assert {"key": "ml_cost_model", "value": "SHADOW"} in solution["sourceStatus"]


def test_graphql_returns_ml_workflow_status() -> None:
    response = client.post(
        "/graphql",
        json={
            "query": """
                query MlStatus {
                  mlWorkflowStatus {
                    mode
                    servedToUsers
                    activeModelVersion
                    featureSchemaVersion
                    trainingReadiness {
                      label
                      ready
                      detail
                    }
                  }
                }
            """,
        },
    )

    assert response.status_code == 200
    payload = response.json()
    assert "errors" not in payload
    status = payload["data"]["mlWorkflowStatus"]
    assert status["mode"] == "SHADOW_DISABLED"
    assert status["servedToUsers"] is False
    assert status["featureSchemaVersion"] == "edge-cost-v1"
    assert any(item["label"] == "route_observations" for item in status["trainingReadiness"])
