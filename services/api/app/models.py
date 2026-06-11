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


class VehicleType(StrEnum):
    CAR = "CAR"
    VAN = "VAN"
    TRUCK = "TRUCK"


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
    data_status: str = "LIVE"
    source: str = "Open-Meteo"


class NationalWeatherSnapshot(BaseModel):
    generated_at: datetime
    expires_at: datetime
    model_version: str = "interest-grid-v0.1"
    refresh_minutes: int = 60
    coverage: float
    points: list[WeatherRisk]
    source_status: dict[str, str] = Field(default_factory=dict)


class WeatherRasterManifest(BaseModel):
    generated_at: datetime
    expires_at: datetime
    layer: str
    source: str
    url: str
    bounds: list[list[float]]
    point_count: int
    coverage: float
    model_version: str = "weather-raster-v0.1"


class HazardExposure(BaseModel):
    category: str
    score: int
    samples_affected: int
    summary: str


class RouteAlternative(BaseModel):
    alternative_id: str
    label: str
    coordinates: list[list[float]]
    distance_miles: float
    duration_minutes: float
    climate_delay_minutes: float
    risk_score: int
    weather: list[WeatherRisk]
    hazards: list[HazardExposure] = Field(default_factory=list)
    model_version: str = "route-risk-v0.2"
    data_coverage: float = 0
    confidence: str = "UNAVAILABLE"
    source_status: dict[str, str] = Field(default_factory=dict)


class Place(BaseModel):
    place_id: str
    display_name: str
    city: str
    state: str
    latitude: float
    longitude: float


class DirectionsRequest(BaseModel):
    origin: Place
    destination: Place
    vehicle_type: VehicleType = VehicleType.CAR


class DirectionsPlan(BaseModel):
    generated_at: datetime
    origin: Place
    destination: Place
    vehicle_type: VehicleType
    coordinates: list[list[float]]
    distance_miles: float
    duration_minutes: float
    climate_delay_minutes: float
    risk_score: int
    weather: list[WeatherRisk]
    summary: str
    alternatives: list[RouteAlternative] = Field(default_factory=list)
    model_version: str = "route-risk-v0.2"


class RiskAlert(BaseModel):
    alert_id: str
    event: str
    severity: str
    urgency: str
    certainty: str
    headline: str
    area: str
    instruction: str | None = None
    score: int
    longitude: float | None = None
    latitude: float | None = None
    geometry: dict | None = None
    category: str = "OTHER"


class NationalRiskOverview(BaseModel):
    generated_at: datetime
    score: int
    level: str
    active_alerts: int
    severe_alerts: int
    alerts_with_geometry: int
    alerts: list[RiskAlert]
    by_event: dict[str, int]
    source_status: dict[str, str] = Field(default_factory=dict)


class LocationRisk(BaseModel):
    generated_at: datetime
    place: Place
    score: int
    level: str
    summary: str
    factors: dict[str, int]
    alerts: list[RiskAlert]
    weather: WeatherRisk
    model_version: str = "location-risk-v0.2"
    source_status: dict[str, str] = Field(default_factory=dict)


class OptimizedRoute(BaseModel):
    route_id: str
    driver_id: str
    vehicle_type: VehicleType
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
