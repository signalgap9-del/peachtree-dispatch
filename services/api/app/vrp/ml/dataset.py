from __future__ import annotations

import json
from collections.abc import Iterable
from pathlib import Path

from pydantic import AliasChoices, BaseModel, ConfigDict, Field

from .features import EdgeFeatureVector
from ..models import EdgeCost
from .features import edge_cost_to_feature_vector


MILES_TO_METERS = 1609.344


class SavedRouteTrainingExamplePayload(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    observation_id: str = Field(validation_alias=AliasChoices("observation_id", "observationId"))
    saved_item_id: str = Field(validation_alias=AliasChoices("saved_item_id", "savedItemId"))
    feature_schema_version: str = Field(
        default="saved-route-observation-v1",
        validation_alias=AliasChoices("feature_schema_version", "featureSchemaVersion"),
    )
    vehicle_type: str = Field(validation_alias=AliasChoices("vehicle_type", "vehicleType"))
    distance_miles: float = Field(ge=0, validation_alias=AliasChoices("distance_miles", "distanceMiles"))
    planned_duration_minutes: float = Field(
        gt=0,
        validation_alias=AliasChoices("planned_duration_minutes", "plannedDurationMinutes"),
    )
    climate_delay_minutes: float = Field(
        default=0,
        ge=0,
        validation_alias=AliasChoices("climate_delay_minutes", "climateDelayMinutes"),
    )
    planned_risk_score: int = Field(
        ge=0,
        le=100,
        validation_alias=AliasChoices("planned_risk_score", "plannedRiskScore"),
    )
    generated_at: str | None = Field(default=None, validation_alias=AliasChoices("generated_at", "generatedAt"))
    observed_at: str = Field(validation_alias=AliasChoices("observed_at", "observedAt"))
    actual_duration_minutes: float = Field(
        gt=0,
        validation_alias=AliasChoices("actual_duration_minutes", "actualDurationMinutes"),
    )
    delay_label_minutes: float = Field(
        validation_alias=AliasChoices("delay_label_minutes", "delayLabelMinutes"),
    )
    observed_risk_score: int = Field(
        ge=0,
        le=100,
        validation_alias=AliasChoices("observed_risk_score", "observedRiskScore"),
    )
    planned_hazards: list[str] = Field(
        default_factory=list,
        validation_alias=AliasChoices("planned_hazards", "plannedHazards"),
    )
    encountered_hazards: list[str] = Field(
        default_factory=list,
        validation_alias=AliasChoices("encountered_hazards", "encounteredHazards"),
    )
    weather_summary: str | None = Field(default=None, validation_alias=AliasChoices("weather_summary", "weatherSummary"))
    road_event_summary: str | None = Field(default=None, validation_alias=AliasChoices("road_event_summary", "roadEventSummary"))
    source: str = "USER_REPORTED"


class DelayTrainingExample(BaseModel):
    model_config = ConfigDict(frozen=True)

    source_id: str = Field(min_length=1, max_length=160)
    feature: EdgeFeatureVector
    observed_delay_seconds: float = Field(ge=0)
    planned_duration_seconds: float = Field(gt=0)
    actual_duration_seconds: float = Field(gt=0)
    label_source: str = Field(default="saved-route-observation", min_length=1, max_length=80)


def saved_route_example_to_delay_training_example(
    example: SavedRouteTrainingExamplePayload,
) -> DelayTrainingExample:
    planned_hazard_text = " ".join(example.planned_hazards).lower()
    encountered_text = " ".join(example.encountered_hazards).lower()
    flood_score = 70 if "flood" in planned_hazard_text or "flood" in encountered_text else 0
    traffic_score = 55 if any(term in encountered_text for term in ("closure", "work", "incident", "traffic")) else 0
    alert_score = example.planned_risk_score if example.planned_hazards else 0
    weather_score = max(example.planned_risk_score, round(example.climate_delay_minutes * 4))
    primary_hazard = (
        "Flood"
        if flood_score
        else "Road event"
        if traffic_score
        else example.planned_hazards[0]
        if example.planned_hazards
        else None
    )
    edge = EdgeCost(
        from_node_id=f"saved-route:{example.saved_item_id}:origin",
        to_node_id=f"saved-route:{example.saved_item_id}:destination",
        base_duration_seconds=example.planned_duration_minutes * 60,
        base_distance_meters=example.distance_miles * MILES_TO_METERS,
        weather_risk_score=min(100, max(0, weather_score)),
        traffic_risk_score=min(100, max(0, traffic_score)),
        flood_risk_score=min(100, max(0, flood_score)),
        alert_risk_score=min(100, max(0, alert_score)),
        adjusted_cost_seconds=round((example.planned_duration_minutes + example.climate_delay_minutes) * 60),
        primary_hazard=primary_hazard,
        explanation=["saved route observation converted to delay training example"],
    )
    return DelayTrainingExample(
        source_id=example.observation_id,
        feature=edge_cost_to_feature_vector(edge, scenario_id=example.saved_item_id, vehicle_type=example.vehicle_type),
        observed_delay_seconds=max(0, example.delay_label_minutes * 60),
        planned_duration_seconds=example.planned_duration_minutes * 60,
        actual_duration_seconds=example.actual_duration_minutes * 60,
    )


def saved_route_examples_to_delay_dataset(
    examples: Iterable[SavedRouteTrainingExamplePayload],
) -> list[DelayTrainingExample]:
    return [saved_route_example_to_delay_training_example(example) for example in examples]


def edge_cost_to_delay_training_example(
    edge: EdgeCost,
    *,
    observed_delay_seconds: float,
    source_id: str,
    scenario_id: str | None = None,
    vehicle_type: str | None = None,
) -> DelayTrainingExample:
    return DelayTrainingExample(
        source_id=source_id,
        feature=edge_cost_to_feature_vector(edge, scenario_id=scenario_id, vehicle_type=vehicle_type),
        observed_delay_seconds=max(0, observed_delay_seconds),
        planned_duration_seconds=max(1, edge.base_duration_seconds),
        actual_duration_seconds=max(1, edge.base_duration_seconds + max(0, observed_delay_seconds)),
        label_source="edge-observation",
    )


def load_saved_route_training_examples(path: Path) -> list[SavedRouteTrainingExamplePayload]:
    raw = path.read_text(encoding="utf-8")
    if not raw.strip():
        return []
    if raw.lstrip().startswith("["):
        values = json.loads(raw)
    else:
        values = [json.loads(line) for line in raw.splitlines() if line.strip()]
    return [SavedRouteTrainingExamplePayload.model_validate(value) for value in values]


def write_delay_training_dataset(path: Path, examples: Iterable[DelayTrainingExample]) -> None:
    payload = [example.model_dump(mode="json") for example in examples]
    path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")
