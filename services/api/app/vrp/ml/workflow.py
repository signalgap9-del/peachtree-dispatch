from __future__ import annotations

import os
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from .features import FEATURE_SCHEMA_VERSION


MLWorkflowMode = Literal["SHADOW_DISABLED", "SHADOW_LOG_ONLY", "SHADOW_EVALUATING", "SERVING_BLOCKED"]


class TrainingReadinessCheck(BaseModel):
    model_config = ConfigDict(frozen=True)

    label: str
    ready: bool
    detail: str


class MLWorkflowStatus(BaseModel):
    model_config = ConfigDict(frozen=True)

    mode: MLWorkflowMode
    served_to_users: bool
    active_model_version: str
    feature_schema_version: str
    training_readiness: list[TrainingReadinessCheck]
    next_actions: list[str] = Field(default_factory=list)


def get_ml_workflow_status() -> MLWorkflowStatus:
    mode = os.getenv("VRP_ML_WORKFLOW_MODE", "SHADOW_DISABLED")
    if mode not in {"SHADOW_DISABLED", "SHADOW_LOG_ONLY", "SHADOW_EVALUATING", "SERVING_BLOCKED"}:
        mode = "SHADOW_DISABLED"
    return MLWorkflowStatus(
        mode=mode,
        served_to_users=False,
        active_model_version=os.getenv("VRP_ML_MODEL_VERSION", "none"),
        feature_schema_version=FEATURE_SCHEMA_VERSION,
        training_readiness=[
            TrainingReadinessCheck(
                label="route_observations",
                ready=False,
                detail="Persist observed route outcomes before training delay/risk models.",
            ),
            TrainingReadinessCheck(
                label="weather_join",
                ready=False,
                detail="Join NOAA/NWS weather, flood, and winter-condition snapshots to route legs.",
            ),
            TrainingReadinessCheck(
                label="road_event_join",
                ready=False,
                detail="Join WZDx/511 construction, closure, and incident events to route geometry.",
            ),
            TrainingReadinessCheck(
                label="backtest_gate",
                ready=False,
                detail="Require offline backtests to beat the rule-based scorer before serving predictions.",
            ),
        ],
        next_actions=[
            "Add route observation persistence for planned vs. actual duration and hazard exposure.",
            "Build an offline dataset exporter from edge-cost-v1 feature vectors.",
            "Run ML predictions in shadow mode only until backtests pass release thresholds.",
        ],
    )
