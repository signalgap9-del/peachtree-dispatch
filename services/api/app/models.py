from datetime import datetime
from enum import StrEnum

from pydantic import BaseModel, Field


class DeliveryStatus(StrEnum):
    CREATED = "CREATED"
    ASSIGNED = "ASSIGNED"
    PICKED_UP = "PICKED_UP"
    IN_TRANSIT = "IN_TRANSIT"
    DELIVERED = "DELIVERED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


class Location(BaseModel):
    city: str = Field(min_length=1, max_length=80)
    state: str = Field(min_length=2, max_length=2)
    address: str | None = Field(default=None, min_length=1, max_length=200)
    latitude: float | None = None
    longitude: float | None = None


class CreateDelivery(BaseModel):
    origin: Location
    destination: Location
    promised_at: datetime
    driver_id: str | None = None


class AssignDriver(BaseModel):
    driver_id: str = Field(min_length=1, max_length=80)


class RecordEvent(BaseModel):
    event_id: str = Field(min_length=1, max_length=120)
    to_status: DeliveryStatus
    source: str = Field(default="operator-console", min_length=1, max_length=80)
    occurred_at: datetime | None = None


class DeliveryEvent(BaseModel):
    event_id: str
    event_type: str
    from_status: DeliveryStatus | None
    to_status: DeliveryStatus
    source: str
    occurred_at: datetime


class Delivery(BaseModel):
    delivery_id: str
    organization_id: str
    status: DeliveryStatus
    driver_id: str | None
    origin: Location
    destination: Location
    promised_at: datetime
    created_at: datetime
    updated_at: datetime
    version: int
    events: list[DeliveryEvent] = Field(default_factory=list)


class DeliverySummary(BaseModel):
    delivery_id: str
    status: DeliveryStatus
    driver_id: str | None
    origin: Location
    destination: Location
    promised_at: datetime
    updated_at: datetime
    version: int


class DashboardSummary(BaseModel):
    total: int
    active: int
    delivered: int
    failed: int
    delayed: int
    by_status: dict[str, int]


class WeatherRisk(BaseModel):
    id: str
    city: str
    latitude: float
    longitude: float
    temperature_f: float
    precipitation_probability: float
    wind_speed_mph: float
    risk_score: int
    risk_level: str


class OptimizedRoute(BaseModel):
    route_id: str
    driver_id: str
    color: str
    delivery_ids: list[str]
    coordinates: list[list[float]]
    distance_miles: float
    duration_minutes: float
    climate_delay_minutes: float
    risk_score: int
    optimization_note: str
    stops: list["RouteStop"]


class RouteStop(BaseModel):
    sequence: int
    delivery_id: str
    address: str
    city: str
    state: str
    latitude: float
    longitude: float
    promised_at: datetime
    risk_score: int


class NetworkOverview(BaseModel):
    generated_at: datetime
    routes: list[OptimizedRoute]
    weather: list[WeatherRisk]
    algorithm: str
    total_distance_miles: float
    avoided_risk_minutes: float


class OptimizationStatus(StrEnum):
    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"


class OptimizationJob(BaseModel):
    job_id: str
    status: OptimizationStatus
    created_at: datetime
    updated_at: datetime
    result: NetworkOverview | None = None
    error: str | None = None
