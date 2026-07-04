from __future__ import annotations

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from .dataset import SavedRouteTrainingExamplePayload, saved_route_examples_to_delay_dataset
from .features import EdgeFeatureVector
from .shadow_cost_model import ShadowCostPrediction, load_shadow_cost_model_from_env
from .trainer import DelayModelTrainingConfig, DelayModelTrainingResult, train_delay_model
from .workflow import MLWorkflowStatus, get_ml_workflow_status

router = APIRouter(tags=["ml-workflow"])


class DelayModelBacktestRequest(BaseModel):
    examples: list[SavedRouteTrainingExamplePayload] = Field(min_length=4)
    config: DelayModelTrainingConfig = Field(default_factory=DelayModelTrainingConfig)


class ShadowDelayPredictionRequest(BaseModel):
    feature: EdgeFeatureVector


@router.get("/ml/vrp/workflow/status", response_model=MLWorkflowStatus)
def ml_workflow_status() -> MLWorkflowStatus:
    return get_ml_workflow_status()


@router.post("/ml/vrp/delay-model/backtest", response_model=DelayModelTrainingResult)
def backtest_delay_model(request: DelayModelBacktestRequest) -> DelayModelTrainingResult:
    try:
        return train_delay_model(saved_route_examples_to_delay_dataset(request.examples), request.config)
    except ValueError as error:
        raise HTTPException(status_code=400, detail=str(error)) from error


@router.post("/ml/vrp/delay-model/predict", response_model=ShadowCostPrediction)
def predict_shadow_delay(request: ShadowDelayPredictionRequest) -> ShadowCostPrediction:
    return load_shadow_cost_model_from_env().predict(request.feature)
