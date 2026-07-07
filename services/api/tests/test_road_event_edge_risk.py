from app.vrp.edge_risk import ConstantEdgeRiskProvider
from app.vrp.models import GeoNode
from app.vrp.road_event_risk import (
    RoadEvent,
    RoadEventEdgeRiskProvider,
    StaticRoadEventProvider,
    distance_point_to_segment_miles,
    road_events_from_geojson,
)


def test_road_event_join_raises_edge_traffic_risk_for_corridor_event() -> None:
    origin = GeoNode(node_id="atl", label="Atlanta", latitude=33.749, longitude=-84.388)
    destination = GeoNode(node_id="sav", label="Savannah", latitude=32.0809, longitude=-81.0998)
    event = RoadEvent(
        event_id="gdott-1",
        event_type="Road Closure",
        severity="Full Closure",
        latitude=32.8407,
        longitude=-83.6324,
        source="Georgia 511",
        description="I-16 closed near Macon",
        risk_score=85,
    )
    provider = RoadEventEdgeRiskProvider(
        ConstantEdgeRiskProvider(0),
        StaticRoadEventProvider([event], corridor_radius_miles=60),
    )

    risk = provider.score_edge(origin, destination)

    assert risk.traffic_risk_score == 85
    assert risk.primary_hazard == "Road Closure"
    assert "road_event_join=1 corridor events" in risk.explanation
    assert provider.source_status == "CONSTANT_FIXTURE+STATIC_FIXTURE"


def test_road_event_join_ignores_events_outside_corridor() -> None:
    origin = GeoNode(node_id="atl", label="Atlanta", latitude=33.749, longitude=-84.388)
    destination = GeoNode(node_id="sav", label="Savannah", latitude=32.0809, longitude=-81.0998)
    event = RoadEvent(
        event_id="far-1",
        event_type="Road Closure",
        severity="Full Closure",
        latitude=25.7617,
        longitude=-80.1918,
        source="Florida 511",
        description="Miami closure",
        risk_score=85,
    )
    provider = RoadEventEdgeRiskProvider(
        ConstantEdgeRiskProvider(0),
        StaticRoadEventProvider([event], corridor_radius_miles=15),
    )

    risk = provider.score_edge(origin, destination)

    assert risk.traffic_risk_score == 0
    assert risk.primary_hazard is None


def test_wzdx_geojson_features_normalize_to_road_events_without_query_secrets() -> None:
    decoded = {
        "type": "FeatureCollection",
        "features": [
            {
                "type": "Feature",
                "id": "work-zone-1",
                "geometry": {"type": "Point", "coordinates": [-83.6324, 32.8407]},
                "properties": {
                    "event_type": "lane_closure",
                    "vehicle_impact": "all_lanes_closed",
                    "description": "I-16 lane closure near Macon",
                },
            },
            {
                "type": "Feature",
                "geometry": {
                    "type": "LineString",
                    "coordinates": [[-84.4, 33.7], [-84.0, 33.5]],
                },
                "properties": {"eventType": "work_zone", "severity": "minor"},
            },
        ],
    }

    events = road_events_from_geojson(decoded, "https://511.example.test/feed?api_key=secret", limit=10)

    assert len(events) == 2
    assert events[0].event_type == "Lane Closure"
    assert events[0].risk_score == 85
    assert events[0].source == "https://511.example.test/feed"
    assert events[1].event_type == "Work Zone"


def test_distance_point_to_segment_is_zero_for_point_on_segment() -> None:
    distance = distance_point_to_segment_miles(33.0, -83.0, 34.0, -84.0, 32.0, -82.0)

    assert distance < 1
