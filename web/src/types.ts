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

export interface NationalWeatherSnapshot {
  generated_at: string;
  expires_at: string;
  model_version: string;
  refresh_minutes: number;
  coverage: number;
  points: WeatherRisk[];
  source_status: Record<string, string>;
}

export interface WeatherRasterManifest {
  generated_at: string;
  expires_at: string;
  layer: string;
  source: string;
  url: string;
  bounds: number[][];
  point_count: number;
  coverage: number;
  model_version: string;
}

export type VehicleType = "CAR" | "VAN" | "TRUCK";
export type StopKind = "DEPOT" | "PICKUP" | "DELIVERY" | "WAYPOINT" | "REST" | "FINAL";
export type MultiStopMode = "MANUAL_ORDER" | "OPTIMIZE_ORDER";

export interface Place {
  place_id: string;
  display_name: string;
  city: string;
  state: string;
  latitude: number;
  longitude: number;
}

export interface SavedPlaceRecord {
  savedItemId: string;
  userId: string;
  name: string;
  longitude: number;
  latitude: number;
  currentRiskScore: number | null;
}

export interface SavedRouteRecord {
  savedItemId: string;
  userId: string;
  name: string;
  originName: string;
  destinationName: string;
  vehicleType: VehicleType;
  distanceMiles: number;
  durationMinutes: number;
  climateDelayMinutes: number;
  riskScore: number;
  coordinates: number[][];
  generatedAt: string | null;
  usualDepartureTime: string;
  riskThreshold: number;
  monitorEnabled: boolean;
  lastCheckedAt: string | null;
  activeHazards: string[];
  riskTrend: "IMPROVING" | "STABLE" | "WORSENING" | string;
}

export interface SavedRouteRisk {
  savedItemId: string;
  currentRiskScore: number;
  thresholdExceeded: boolean;
  lastCheckedAt: string | null;
  activeHazards: string[];
  riskTrend: string;
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
  decision?: RouteDecisionContract | null;
  segments?: RouteRiskSegmentContract[];
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

export type RouteDecisionAction = "take_fastest" | "take_lower_risk" | "take_balanced" | "delay_departure";

export interface RouteDecision {
  action: RouteDecisionAction;
  recommendedAlternativeId: string;
  recommendedLabel: string;
  summary: string;
  primaryReason: string;
  tradeoff: string;
  confidence: "HIGH" | "MEDIUM" | "LOW" | "UNAVAILABLE";
  riskDelta: number;
  timeDeltaMinutes: number;
  severity: "low" | "moderate" | "high" | "severe";
}

export type HazardKind = "flood" | "rain" | "wind" | "heat" | "winter" | "alert" | "unknown";

export interface RouteRiskSegment {
  id: string;
  label: string;
  riskScore: number;
  severity: "low" | "moderate" | "high" | "severe";
  primaryHazard: HazardKind;
  coverage: number;
  summary: string;
}

export interface RouteDecisionContract {
  action: "TAKE_FASTEST" | "TAKE_LOWER_RISK" | "TAKE_BALANCED" | "DELAY_DEPARTURE";
  recommended_alternative_id: string;
  recommended_label: string;
  summary: string;
  primary_reason: string;
  tradeoff: string;
  confidence: "HIGH" | "MEDIUM" | "LOW" | "UNAVAILABLE";
  risk_delta: number;
  time_delta_minutes: number;
  severity: "LOW" | "MODERATE" | "HIGH" | "SEVERE";
}

export interface RouteRiskSegmentContract {
  segment_id: string;
  label: string;
  risk_score: number;
  severity: "LOW" | "MODERATE" | "HIGH" | "SEVERE";
  primary_hazard: "FLOOD" | "RAIN" | "WIND" | "HEAT" | "WINTER" | "ALERT" | "UNKNOWN";
  coverage: number;
  summary: string;
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

export interface RouteStop {
  stop_id: string;
  kind: StopKind;
  name: string;
  latitude: number;
  longitude: number;
  address?: string | null;
  city?: string | null;
  state?: string | null;
  sequence?: number | null;
  demand_units?: number;
  service_duration_minutes?: number;
  time_window_start?: string | null;
  time_window_end?: string | null;
  required_vehicle_type?: VehicleType | null;
}

export interface MultiStopRouteRequest {
  mode: MultiStopMode;
  vehicle_type: VehicleType;
  stops: RouteStop[];
  start_stop_id?: string | null;
  end_stop_id?: string | null;
  objective?: "duration" | "risk_adjusted_time";
  risk_model?: {
    weather_risk_weight?: number;
    traffic_risk_weight?: number;
    flood_risk_weight?: number;
    alert_risk_weight?: number;
  };
}

export interface RouteLeg {
  from_stop_id: string;
  to_stop_id: string;
  sequence: number;
  distance_miles: number;
  duration_minutes: number;
  risk_adjusted_duration_minutes: number;
  risk_score: number;
  primary_hazard?: string | null;
  geometry: { type: "LineString"; coordinates: number[][]; sourceStatus?: string };
  explanation: string[];
}

export interface MultiStopRoutePlan {
  route_id: string;
  mode: MultiStopMode;
  vehicle_type: VehicleType;
  submitted_sequence: string[];
  optimized_sequence?: string[] | null;
  sequence_changed: boolean;
  explanation: string[];
  total_distance_miles: number;
  total_duration_minutes: number;
  risk_adjusted_duration_minutes: number;
  route_risk_score: number;
  legs: RouteLeg[];
  source_status: Record<string, string>;
}

export interface VRPScenario {
  scenario_id?: string;
  depot: {
    depot_id?: string;
    name: string;
    location: { latitude: number; longitude: number };
  };
  vehicles: Array<{
    vehicle_id: string;
    vehicle_type?: VehicleType;
    capacity_units?: number;
    start_location: { latitude: number; longitude: number };
    end_location?: { latitude: number; longitude: number } | null;
    shift_start?: string | null;
    shift_end?: string | null;
  }>;
  jobs: Array<{
    job_id: string;
    name: string;
    location: { latitude: number; longitude: number };
    demand_units?: number;
    service_duration_minutes?: number;
    time_window_start?: string | null;
    time_window_end?: string | null;
    drop_penalty?: number;
  }>;
  objective?: "duration" | "risk_adjusted_time";
  solver?: "ortools" | "pyvrp";
}

export interface VRPSolution {
  solution_id: string;
  solver: string;
  status: "OPTIMAL" | "FEASIBLE" | "INFEASIBLE" | "ERROR";
  objective_value: number;
  solve_time_ms: number;
  routes: Array<{
    vehicle_id: string;
    vehicle_type: VehicleType;
    total_duration_minutes: number;
    total_distance_miles: number;
    risk_adjusted_duration_minutes: number;
    risk_exposure_score: number;
    stops: Array<{
      job_id: string;
      sequence: number;
      eta?: string | null;
      arrival_window_status: "EARLY" | "ON_TIME" | "LATE" | "UNKNOWN";
      leg_duration_minutes: number;
      leg_distance_miles: number;
      leg_risk_score: number;
      primary_hazard?: string | null;
    }>;
  }>;
  dropped_jobs: string[];
  source_status: Record<string, string>;
}

export interface RoadEventFeed {
  feed_id: string;
  state: string;
  issuing_organization: string;
  feed_name: string;
  format: string;
  version?: string | null;
  update_frequency?: string | null;
  active: boolean;
  requires_api_key: boolean;
  endpoint_host?: string | null;
  longitude?: number | null;
  latitude?: number | null;
}

export interface RoadEventFeedRegistry {
  generated_at: string;
  source: string;
  active_feeds: number;
  no_key_feeds: number;
  feeds: RoadEventFeed[];
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
