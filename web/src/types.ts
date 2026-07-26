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

export type PlanCode = "FREE" | "PRO" | "TEAM" | "INTERNAL";
export type SubscriptionStatus = "TRIALING" | "ACTIVE" | "PAST_DUE" | "SUSPENDED";
export type MeteredFeature = "ROUTE_PLAN" | "PLACE_SEARCH" | "LOCATION_RISK" | "ALERT_SEARCH" | "SAVED_ROUTE" | "SAVED_PLACE";

export interface FeatureUsage {
  feature: MeteredFeature;
  label: string;
  used: number;
  limit: number;
  remaining: number;
  resetsAt: string;
  exceeded: boolean;
}

export interface CapacityUsage {
  feature: MeteredFeature;
  label: string;
  used: number;
  limit: number;
  remaining: number;
  exceeded: boolean;
}

export interface AccountSummary {
  user: {
    userId: string;
    subject: string;
    email: string;
  };
  workspace: {
    tenantId: string;
    name: string;
    role: string;
  };
  plan: {
    code: PlanCode;
    status: SubscriptionStatus;
    savedRouteHistoryDays: number;
    dispatchOptimizerEnabled: boolean;
    teamWorkspaceEnabled: boolean;
  };
  dailyUsage: FeatureUsage[];
  savedRoutes: CapacityUsage;
  savedPlaces: CapacityUsage;
  readiness: Array<{
    key: string;
    label: string;
    state: "READY" | "ENFORCED" | "CONFIGURABLE" | "PUBLIC_PREVIEW" | string;
    detail: string;
  }>;
}

export interface ApiErrorEnvelope {
  error?: {
    code?: string;
    message?: string;
    requestId?: string;
    details?: Record<string, unknown>;
  };
  detail?: string;
}

// ---- Billing (Lemon Squeezy hosted checkout + portal, proxied by the platform API) ----

/** Raw shape of GET /api/v1/billing/subscription. */
export interface BillingSubscription {
  plan: PlanCode | string;
  status: SubscriptionStatus | string;
  currentPeriodEnd: string | null;
  cancelAtPeriodEnd: boolean;
  manageUrl?: string;
}

/** Raw shape of POST /api/v1/billing/checkout. */
export interface BillingCheckoutResponse {
  checkoutUrl: string;
}

/** Raw shape of POST /api/v1/billing/portal. */
export interface BillingPortalResponse {
  portalUrl: string;
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
  /** Risk threshold used for the smart default recommendation. */
  risk_threshold?: number;
  /** True when the fastest route's risk stays below the threshold. */
  low_risk?: boolean;
  /** Which alternative the AI recommends: the fastest or the lower-risk one. */
  recommended?: "fastest" | "lower_risk";
  /** Plain-English explanation of the smart default decision. */
  recommendation_reason?: string;
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

// ---- LLM integration (Phase 5) ----

export type ChatRole = "user" | "assistant" | "system";

export interface ChatMessage {
  role: ChatRole;
  content: string;
}

/** Proactive risk suggestion pushed over the alert SSE stream (`risk_suggestion` event). */
export type RiskSuggestion = {
  id: string;
  routeId: string;
  routeName: string;
  severity: string;
  suggestionText: string;
  currentRisk: number;
  alternativeRisk?: number;
  alternativeRoute?: string;
  createdAt: string;
};

/** UI-facing LLM service status, merged from /llm/status and /rag/health. */
export type LlmStatus = {
  enabled: boolean;
  model: string;
  dailyTokensUsed: number;
  dailyTokenBudget: number;
  ragEnabled: boolean;
  ragVectorCount?: number;
};

/** Raw shape of GET /api/v1/llm/status. */
export interface LlmStatusResponse {
  enabled: boolean;
  model: string;
  dailyBudgetUsed: number;
  dailyBudgetRemaining: number;
}

/** Raw shape of GET /api/v1/rag/health. */
export interface RagHealthResponse {
  status: "UP" | "DOWN" | string;
  searchAvailable: boolean;
  indexCount: number;
}
