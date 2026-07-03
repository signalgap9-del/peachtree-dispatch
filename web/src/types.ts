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
