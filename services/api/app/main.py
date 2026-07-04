import os
from collections import OrderedDict
from datetime import UTC, datetime
from threading import Lock
from time import monotonic
from typing import Callable, TypeVar
from uuid import uuid4

from fastapi import FastAPI, HTTPException, Query, Response, status
from fastapi.middleware.cors import CORSMiddleware

from .models import (
    AssignDriver,
    CreateDelivery,
    DashboardSummary,
    Delivery,
    DeliveryStatus,
    DeliverySummary,
    DirectionsPlan,
    DirectionsRequest,
    LocationRisk,
    NationalRiskOverview,
    NationalWeatherSnapshot,
    WeatherRasterManifest,
    NetworkOverview,
    Place,
    RoadEventFeedRegistry,
    VehicleType,
    OptimizationJob,
    RecordEvent,
)
from .directions import build_directions, search_places
from .risk import location_risk, national_risk
from .weather_snapshot import get_weather_snapshot
from .weather_raster import get_weather_raster_manifest, get_weather_raster_png
from .road_events import get_road_event_feeds
from .network import build_network
from .repository_contract import DuplicateEventError
from .repository_factory import create_repository
from .optimization_service import OptimizationService
from .vrp.ml.routes import router as ml_workflow_router
from .vrp.routes import router as route_engine_router

app = FastAPI(title="AtmosPath Internal Risk Engine", version="0.2.0")
cors_origins = os.getenv(
    "CORS_ORIGINS",
    "http://localhost:5173,http://127.0.0.1:5173",
).split(",")
app.add_middleware(
    CORSMiddleware,
    allow_origins=cors_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

repository = create_repository()
repository.seed()
optimization_service = OptimizationService()
app.include_router(route_engine_router)
app.include_router(ml_workflow_router)

T = TypeVar("T")
_CACHE_TTL_SECONDS = int(os.getenv("RISK_ENGINE_CACHE_TTL_SECONDS", "60"))
_CACHE_MAX_ITEMS = int(os.getenv("RISK_ENGINE_CACHE_MAX_ITEMS", "128"))
_cache_lock = Lock()
_response_cache: OrderedDict[str, tuple[float, object]] = OrderedDict()
_key_locks: dict[str, Lock] = {}


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "healthy", "service": "atmospath-risk-engine"}


@app.get("/dashboard", response_model=DashboardSummary)
def dashboard() -> DashboardSummary:
    deliveries = repository.list()
    by_status = {delivery_status: 0 for delivery_status in DeliveryStatus}
    for delivery in deliveries:
        by_status[delivery.status] += 1
    now = datetime.now(UTC)
    return DashboardSummary(
        total=len(deliveries),
        active=sum(
            by_status[item]
            for item in (
                DeliveryStatus.CREATED,
                DeliveryStatus.ASSIGNED,
                DeliveryStatus.PICKED_UP,
                DeliveryStatus.IN_TRANSIT,
            )
        ),
        delivered=by_status[DeliveryStatus.DELIVERED],
        failed=by_status[DeliveryStatus.FAILED],
        delayed=sum(
            1
            for delivery in deliveries
            if delivery.promised_at < now
            and delivery.status not in (DeliveryStatus.DELIVERED, DeliveryStatus.CANCELLED)
        ),
        by_status=by_status,
    )


@app.get("/network", response_model=NetworkOverview)
def network(vehicle_type: VehicleType | None = None) -> NetworkOverview:
    return build_network(repository.list(), vehicle_type)


@app.get("/places/search", response_model=list[Place])
def places_search(q: str = Query(min_length=2, max_length=160)) -> list[Place]:
    normalized = q.strip().lower()
    return _cached_response(f"places-search:{normalized}", lambda: search_places(q))


@app.post("/directions", response_model=DirectionsPlan)
def directions(command: DirectionsRequest) -> DirectionsPlan:
    return _cached_response(
        f"directions:{command.model_dump_json()}",
        lambda: build_directions(command),
    )


@app.get("/risk/national", response_model=NationalRiskOverview)
def risk_national() -> NationalRiskOverview:
    return _cached_response("risk-national", national_risk)


@app.get("/risk/weather-snapshot", response_model=NationalWeatherSnapshot)
def risk_weather_snapshot() -> NationalWeatherSnapshot:
    return _cached_response("risk-weather-snapshot", get_weather_snapshot)


@app.get("/risk/weather-raster", response_model=WeatherRasterManifest)
def risk_weather_raster() -> WeatherRasterManifest:
    return _cached_response("risk-weather-raster", get_weather_raster_manifest)


@app.get("/risk/weather-raster.png")
def risk_weather_raster_png() -> Response:
    return Response(content=get_weather_raster_png(), media_type="image/png")


@app.post("/risk/location", response_model=LocationRisk)
def risk_location(place: Place) -> LocationRisk:
    return _cached_response(
        f"location-risk:{place.model_dump_json()}",
        lambda: location_risk(place),
    )


@app.get("/road-events/feeds", response_model=RoadEventFeedRegistry)
def road_event_feeds(
    state: str | None = Query(None, min_length=2, max_length=40),
    limit: int = Query(30, ge=1, le=100),
) -> RoadEventFeedRegistry:
    normalized_state = state.strip().lower() if state else "all"
    return _cached_response(f"road-events:{normalized_state}:{limit}", lambda: get_road_event_feeds(state=state, limit=limit))


@app.post(
    "/optimizations",
    response_model=OptimizationJob,
    status_code=status.HTTP_202_ACCEPTED,
)
def submit_optimization() -> OptimizationJob:
    return optimization_service.submit()


@app.get("/optimizations/{job_id}", response_model=OptimizationJob)
def get_optimization(job_id: str) -> OptimizationJob:
    job = optimization_service.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Optimization job not found")
    return job


@app.get("/deliveries", response_model=list[DeliverySummary])
def list_deliveries(
    delivery_status: DeliveryStatus | None = Query(None, alias="status"),
    driver_id: str | None = None,
    promised_date: str | None = None,
) -> list[DeliverySummary]:
    return repository.list(delivery_status, driver_id, promised_date)


@app.post("/deliveries", response_model=Delivery, status_code=status.HTTP_201_CREATED)
def create_delivery(command: CreateDelivery) -> Delivery:
    return repository.create(f"PD-{uuid4().hex[:8].upper()}", command)


@app.get("/deliveries/{delivery_id}", response_model=Delivery)
def get_delivery(delivery_id: str) -> Delivery:
    delivery = repository.get(delivery_id)
    if not delivery:
        raise HTTPException(status_code=404, detail="Delivery not found")
    return delivery


@app.post("/deliveries/{delivery_id}/assignments", response_model=Delivery)
def assign_driver(delivery_id: str, command: AssignDriver) -> Delivery:
    return _transition(
        delivery_id,
        f"evt-{uuid4()}",
        DeliveryStatus.ASSIGNED,
        "operator-console",
        driver_id=command.driver_id,
    )


@app.post("/deliveries/{delivery_id}/events", response_model=Delivery)
def record_event(delivery_id: str, command: RecordEvent, response: Response) -> Delivery:
    try:
        return _transition(
            delivery_id,
            command.event_id,
            command.to_status,
            command.source,
            command.occurred_at,
        )
    except DuplicateEventError:
        response.status_code = status.HTTP_200_OK
        delivery = repository.get(delivery_id)
        if not delivery:
            raise HTTPException(status_code=404, detail="Delivery not found")
        return delivery


def _transition(
    delivery_id: str,
    event_id: str,
    target: DeliveryStatus,
    source: str,
    occurred_at: datetime | None = None,
    driver_id: str | None = None,
) -> Delivery:
    try:
        return repository.transition(
            delivery_id, event_id, target, source, occurred_at, driver_id
        )
    except KeyError as error:
        raise HTTPException(status_code=404, detail="Delivery not found") from error
    except ValueError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error


def _cached_response(key: str, builder: Callable[[], T]) -> T:
    now = monotonic()
    with _cache_lock:
        cached = _response_cache.get(key)
        if cached and now - cached[0] <= _CACHE_TTL_SECONDS:
            _response_cache.move_to_end(key)
            return cached[1]  # type: ignore[return-value]

    key_lock = _lock_for_key(key)
    with key_lock:
        now = monotonic()
        with _cache_lock:
            cached = _response_cache.get(key)
            if cached and now - cached[0] <= _CACHE_TTL_SECONDS:
                _response_cache.move_to_end(key)
                return cached[1]  # type: ignore[return-value]

        value = builder()
        with _cache_lock:
            _response_cache[key] = (now, value)
            _response_cache.move_to_end(key)
            while len(_response_cache) > _CACHE_MAX_ITEMS:
                evicted_key, _ = _response_cache.popitem(last=False)
                _key_locks.pop(evicted_key, None)
        return value


def _lock_for_key(key: str) -> Lock:
    with _cache_lock:
        key_lock = _key_locks.get(key)
        if key_lock is None:
            key_lock = Lock()
            _key_locks[key] = key_lock
        return key_lock


def _clear_response_cache_for_tests() -> None:
    with _cache_lock:
        _response_cache.clear()
        _key_locks.clear()
