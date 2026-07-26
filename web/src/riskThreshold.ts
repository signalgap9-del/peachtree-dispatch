export const RISK_THRESHOLD_KEY = "freightscaler:risk-threshold";
export const DEFAULT_RISK_THRESHOLD = 40;

export const RISK_THRESHOLD_PRESETS = [
  { label: "Low", value: 25 },
  { label: "Medium", value: 40 },
  { label: "High", value: 60 },
] as const;

export function loadRiskThreshold(): number {
  try {
    const raw = window.localStorage.getItem(RISK_THRESHOLD_KEY);
    if (!raw) return DEFAULT_RISK_THRESHOLD;
    const parsed = Number(JSON.parse(raw));
    if (!Number.isFinite(parsed)) return DEFAULT_RISK_THRESHOLD;
    return Math.min(100, Math.max(1, Math.round(parsed)));
  } catch {
    return DEFAULT_RISK_THRESHOLD;
  }
}

export function saveRiskThreshold(value: number): void {
  try {
    window.localStorage.setItem(RISK_THRESHOLD_KEY, JSON.stringify(value));
  } catch {
    // Storage unavailable; preference applies for this session only.
  }
}
