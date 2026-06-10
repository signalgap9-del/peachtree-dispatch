import os
from pathlib import Path

os.environ["DATABASE_PATH"] = str(Path(__file__).parent / "test.db")

from fastapi.testclient import TestClient

from app.main import app
from app.models import NetworkOverview


client = TestClient(app)


def test_health() -> None:
    assert client.get("/health").json()["status"] == "healthy"


def test_list_seeded_deliveries() -> None:
    response = client.get("/deliveries")
    assert response.status_code == 200
    assert len(response.json()) >= 5


def test_duplicate_event_is_idempotent() -> None:
    delivery_id = "PD-1002"
    payload = {"event_id": "duplicate-test", "to_status": "IN_TRANSIT"}
    first = client.post(f"/deliveries/{delivery_id}/events", json=payload)
    second = client.post(f"/deliveries/{delivery_id}/events", json=payload)
    assert first.status_code == 200
    assert second.status_code == 200
    assert first.json()["version"] == second.json()["version"]


def test_invalid_transition_returns_conflict() -> None:
    response = client.post(
        "/deliveries/PD-1003/events",
        json={"event_id": "invalid-test", "to_status": "DELIVERED"},
    )
    assert response.status_code == 409


def test_network_returns_optimized_routes(monkeypatch) -> None:
    monkeypatch.setattr(
        "app.main.build_network",
        lambda deliveries: NetworkOverview(
            generated_at="2026-06-10T00:00:00Z",
            routes=[],
            weather=[],
            algorithm="test optimizer",
            total_distance_miles=0,
            avoided_risk_minutes=0,
        ),
    )

    response = client.get("/network")

    assert response.status_code == 200
    assert response.json()["algorithm"] == "test optimizer"
