import os
from datetime import UTC, datetime
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
    NetworkOverview,
    Place,
    VehicleType,
    OptimizationJob,
    RecordEvent,
)
from .directions import build_directions, search_places
from .risk import location_risk, national_risk
from .network import build_network
from .repository_contract import DuplicateEventError
from .repository_factory import create_repository
from .optimization_service import OptimizationService

app = FastAPI(title="AtmosPath Internal Risk Engine", version="0.2.0")
cors_origins = os.getenv("CORS_ORIGINS", "http://localhost:5173").split(",")
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
    return search_places(q)


@app.post("/directions", response_model=DirectionsPlan)
def directions(command: DirectionsRequest) -> DirectionsPlan:
    return build_directions(command)


@app.get("/risk/national", response_model=NationalRiskOverview)
def risk_national() -> NationalRiskOverview:
    return national_risk()


@app.post("/risk/location", response_model=LocationRisk)
def risk_location(place: Place) -> LocationRisk:
    return location_risk(place)


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
