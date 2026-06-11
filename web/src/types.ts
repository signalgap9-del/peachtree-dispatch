export type DeliveryStatus =
  | "CREATED"
  | "ASSIGNED"
  | "PICKED_UP"
  | "IN_TRANSIT"
  | "DELIVERED"
  | "FAILED"
  | "CANCELLED";

export interface Location {
  city: string;
  state: string;
  address?: string | null;
  latitude?: number | null;
  longitude?: number | null;
}

export interface DeliverySummary {
  delivery_id: string;
  status: DeliveryStatus;
  driver_id: string | null;
  origin: Location;
  destination: Location;
  promised_at: string;
  updated_at: string;
  version: number;
}

export interface DeliveryEvent {
  event_id: string;
  event_type: string;
  from_status: DeliveryStatus | null;
  to_status: DeliveryStatus;
  source: string;
  occurred_at: string;
}

export interface Delivery extends DeliverySummary {
  organization_id: string;
  created_at: string;
  events: DeliveryEvent[];
}

export interface DashboardSummary {
  total: number;
  active: number;
  delivered: number;
  failed: number;
  delayed: number;
  by_status: Record<DeliveryStatus, number>;
}

export interface WeatherRisk {
  id: string;
  city: string;
  latitude: number;
  longitude: number;
  temperature_f: number;
  precipitation_probability: number;
  wind_speed_mph: number;
  risk_score: number;
  risk_level: "LOW" | "ELEVATED" | "HIGH" | "UNKNOWN";
  data_status?: "LIVE" | "UNAVAILABLE";
  source?: string;
}

export type VehicleType = "CAR" | "VAN" | "TRUCK";

export interface Place {
  place_id: string;
  display_name: string;
  city: string;
  state: string;
  latitude: number;
  longitude: number;
}

export interface DirectionsPlan {
  generated_at: string;
  origin: Place;
  destination: Place;
  vehicle_type: VehicleType;
  coordinates: number[][];
  distance_miles: number;
  duration_minutes: number;
  climate_delay_minutes: number;
  risk_score: number;
  weather: WeatherRisk[];
  summary: string;
  alternatives: RouteAlternative[];
  model_version?: string;
}

export interface HazardExposure {
  category: string;
  score: number;
  samples_affected: number;
  summary: string;
}

export interface RouteAlternative {
  alternative_id: string;
  label: string;
  coordinates: number[][];
  distance_miles: number;
  duration_minutes: number;
  climate_delay_minutes: number;
  risk_score: number;
  weather: WeatherRisk[];
  hazards: HazardExposure[];
  model_version?: string;
  data_coverage?: number;
  confidence?: "HIGH" | "MEDIUM" | "LOW" | "UNAVAILABLE";
  source_status?: Record<string, string>;
}

export interface RiskAlert {
  alert_id: string;
  event: string;
  severity: string;
  urgency: string;
  certainty: string;
  headline: string;
  area: string;
  instruction?: string | null;
  score: number;
  longitude?: number | null;
  latitude?: number | null;
  geometry?: RiskGeometry | null;
  category?: string;
}

export type RiskGeometry =
  | { type: "Polygon"; coordinates: number[][][] }
  | { type: "MultiPolygon"; coordinates: number[][][][] };

export interface NationalRiskOverview {
  generated_at: string;
  score: number;
  level: string;
  active_alerts: number;
  severe_alerts: number;
  alerts_with_geometry: number;
  alerts: RiskAlert[];
  by_event: Record<string, number>;
  source_status?: Record<string, string>;
}

export interface LocationRisk {
  generated_at: string;
  place: Place;
  score: number;
  level: string;
  summary: string;
  factors: Record<string, number>;
  alerts: RiskAlert[];
  weather: WeatherRisk;
  model_version?: string;
  source_status?: Record<string, string>;
}

export interface OptimizedRoute {
  route_id: string;
  driver_id: string;
  vehicle_type: VehicleType;
  color: string;
  delivery_ids: string[];
  coordinates: number[][];
  distance_miles: number;
  duration_minutes: number;
  climate_delay_minutes: number;
  risk_score: number;
  optimization_note: string;
  stops: RouteStop[];
}

export interface RouteStop {
  sequence: number;
  delivery_id: string;
  address: string;
  city: string;
  state: string;
  latitude: number;
  longitude: number;
  promised_at: string;
  risk_score: number;
}

export interface NetworkOverview {
  generated_at: string;
  routes: OptimizedRoute[];
  weather: WeatherRisk[];
  algorithm: string;
  total_distance_miles: number;
  avoided_risk_minutes: number;
}
