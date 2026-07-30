// Freight platform domain types (Phase 2-4 backend services).
// Field names match the Spring Boot JSON serialization (camelCase).

export type CargoType = "GENERAL" | "REFRIGERATED" | "HAZMAT" | "OVERSIZE";

export type LoadStatus =
  | "OPEN"
  | "MATCHED"
  | "DISPATCHED"
  | "IN_TRANSIT"
  | "DELIVERED"
  | "SETTLED"
  | "CANCELLED";

export interface FreightLoad {
  id: number;
  tenantId: string;
  origin: string;
  destination: string;
  cargoType: CargoType;
  weightKg: number | null;
  pickupStart: string | null;
  pickupEnd: string | null;
  deliveryDeadline: string | null;
  maxRateCents: number | null;
  corridorId: string | null;
  corridorRisk: number | null;
  status: LoadStatus;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface LoadPage {
  loads: FreightLoad[];
  nextCursor: number | null;
  hasMore: boolean;
}

export interface CreateLoadRequest {
  tenantId: string;
  origin: string;
  destination: string;
  cargoType?: CargoType;
  weightKg?: number;
  maxRateCents?: number;
  corridorId?: string;
  corridorRisk?: number;
}

export type BidStatus = "SUBMITTED" | "ACCEPTED" | "REJECTED" | "WITHDRAWN";

export interface Bid {
  id: number;
  loadId: number;
  carrierId: string;
  rateCents: number;
  estimatedHours: number | null;
  riskAcknowledgment: boolean;
  status: BidStatus;
  createdAt: string;
}

export interface BidSubmission {
  loadId: number;
  carrierId: string;
  rateCents: number;
  estimatedHours?: number;
  riskAcknowledgment?: boolean;
}

export interface CarrierScore {
  carrierId: string;
  score: number;
  rank: number;
}

export type SettlementStatus =
  | "PENDING"
  | "IN_PROGRESS"
  | "COMPLETED"
  | "FAILED"
  | "DISPUTED";

export interface Settlement {
  id: number;
  loadId: number;
  bidId: number;
  carrierId: string;
  shipperId: string;
  baseRateCents: number;
  adjustmentCents: number;
  finalAmountCents: number | null;
  status: SettlementStatus;
  currentStep: number;
  sagaLog: string;
  createdAt: string;
  completedAt: string | null;
}

export interface Wallet {
  ownerId: string;
  balanceCents: number;
  version: number;
  updatedAt: string;
}

export interface TrackingEvent {
  time: string;
  truckId: string;
  corridorId: string | null;
  lat: number;
  lon: number;
  speedKmh: number | null;
  heading: number | null;
  riskScore: number | null;
}

export interface TrackingHistoryPage {
  data: TrackingEvent[];
  nextCursor: string | null;
}

export interface TelemetryPing {
  truckId: string;
  lat: number;
  lon: number;
  speedKmh: number;
  heading?: number;
  corridorId?: string;
  timestamp: string;
}

/** Corridors used across the freight UI (matches seed data). */
export const CORRIDORS = [
  "I-10", "I-20", "I-40", "I-70", "I-80",
  "I-90", "I-95", "I-5", "I-15", "I-35",
] as const;

export type Corridor = (typeof CORRIDORS)[number];
