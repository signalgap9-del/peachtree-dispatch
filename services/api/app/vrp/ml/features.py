from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field

from ..models import EdgeCost


FEATURE_SCHEMA_VERSION = "edge-cost-v1"
DELAY_MODEL_FEATURE_NAMES = [
    "base_duration_hours",
    "base_distance_100_miles",
    "weather_risk_ratio",
    "traffic_risk_ratio",
    "flood_risk_ratio",
    "alert_risk_ratio",
    "max_risk_ratio",
    "has_primary_hazard",
    "vehicle_type_car",
    "vehicle_type_van",
    "vehicle_type_truck",
]


class EdgeFeatureVector(BaseModel):
    model_config = ConfigDict(frozen=True)

    schema_version: str = FEATURE_SCHEMA_VERSION
    scenario_id: str | None = Field(default=None, max_length=120)
    from_node_id: str = Field(min_length=1, max_length=120)
    to_node_id: str = Field(min_length=1, max_length=120)
    vehicle_type: str | None = Field(default=None, max_length=40)
    base_duration_seconds: float = Field(ge=0)
    base_distance_meters: float = Field(ge=0)
    weather_risk_score: int = Field(ge=0, le=100)
    traffic_risk_score: int = Field(ge=0, le=100)
    flood_risk_score: int = Field(ge=0, le=100)
    alert_risk_score: int = Field(ge=0, le=100)
    max_risk_score: int = Field(ge=0, le=100)
    primary_hazard: str | None = Field(default=None, max_length=160)
    feature_values: dict[str, float]


def edge_cost_to_feature_vector(
    edge: EdgeCost,
    *,
    scenario_id: str | None = None,
    vehicle_type: str | None = None,
) -> EdgeFeatureVector:
    vehicle = (vehicle_type or "UNKNOWN").lower()
    max_risk = max(
        edge.weather_risk_score,
        edge.traffic_risk_score,
        edge.flood_risk_score,
        edge.alert_risk_score,
    )
    feature_values = {
        "base_duration_seconds": float(edge.base_duration_seconds),
        "base_distance_meters": float(edge.base_distance_meters),
        "weather_risk_score": float(edge.weather_risk_score),
        "traffic_risk_score": float(edge.traffic_risk_score),
        "flood_risk_score": float(edge.flood_risk_score),
        "alert_risk_score": float(edge.alert_risk_score),
        "max_risk_score": float(max_risk),
        f"vehicle_type_{vehicle}": 1.0,
        "has_primary_hazard": 1.0 if edge.primary_hazard else 0.0,
    }
    return EdgeFeatureVector(
        scenario_id=scenario_id,
        from_node_id=edge.from_node_id,
        to_node_id=edge.to_node_id,
        vehicle_type=vehicle_type,
        base_duration_seconds=edge.base_duration_seconds,
        base_distance_meters=edge.base_distance_meters,
        weather_risk_score=edge.weather_risk_score,
        traffic_risk_score=edge.traffic_risk_score,
        flood_risk_score=edge.flood_risk_score,
        alert_risk_score=edge.alert_risk_score,
        max_risk_score=max_risk,
        primary_hazard=edge.primary_hazard,
        feature_values=feature_values,
    )


def delay_model_feature_values(feature: EdgeFeatureVector) -> dict[str, float]:
    return {
        "base_duration_hours": feature.base_duration_seconds / 3600,
        "base_distance_100_miles": feature.base_distance_meters / 160_934.4,
        "weather_risk_ratio": feature.weather_risk_score / 100,
        "traffic_risk_ratio": feature.traffic_risk_score / 100,
        "flood_risk_ratio": feature.flood_risk_score / 100,
        "alert_risk_ratio": feature.alert_risk_score / 100,
        "max_risk_ratio": feature.max_risk_score / 100,
        "has_primary_hazard": 1.0 if feature.primary_hazard else 0.0,
        "vehicle_type_car": 1.0 if (feature.vehicle_type or "").upper() == "CAR" else 0.0,
        "vehicle_type_van": 1.0 if (feature.vehicle_type or "").upper() == "VAN" else 0.0,
        "vehicle_type_truck": 1.0 if (feature.vehicle_type or "").upper() == "TRUCK" else 0.0,
    }


def vectorize_delay_features(
    feature: EdgeFeatureVector,
    feature_names: list[str] | tuple[str, ...] = DELAY_MODEL_FEATURE_NAMES,
) -> list[float]:
    values = delay_model_feature_values(feature)
    return [float(values.get(name, 0.0)) for name in feature_names]
