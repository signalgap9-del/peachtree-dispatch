from app.hazards import _geometry_contains, alerts_for_route_samples
from app.models import RiskAlert


def test_polygon_and_hole_containment() -> None:
    geometry = {
        "type": "Polygon",
        "coordinates": [
            [[-81, 25], [-79, 25], [-79, 27], [-81, 27], [-81, 25]],
            [[-80.2, 25.8], [-79.8, 25.8], [-79.8, 26.2], [-80.2, 26.2], [-80.2, 25.8]],
        ],
    }
    assert _geometry_contains(geometry, -80.5, 25.5)
    assert not _geometry_contains(geometry, -80.0, 26.0)
    assert not _geometry_contains(geometry, -82.0, 26.0)


def test_route_samples_use_one_national_alert_snapshot(monkeypatch) -> None:
    calls = 0
    alert = RiskAlert(
        alert_id="flood",
        event="Flood Warning",
        severity="Severe",
        urgency="Immediate",
        certainty="Observed",
        headline="Flooding",
        area="South Florida",
        score=92,
        geometry={
            "type": "Polygon",
            "coordinates": [[[-81, 25], [-79, 25], [-79, 27], [-81, 27], [-81, 25]]],
        },
    )

    def national():
        nonlocal calls
        calls += 1
        return [alert], "LIVE"

    monkeypatch.setattr("app.hazards.national_alerts_result", national)
    matches, status = alerts_for_route_samples(
        [(0, -80.2, 25.7), (1, -82.0, 25.7)]
    )

    assert calls == 1
    assert status == "LIVE"
    assert matches == [[alert], []]
