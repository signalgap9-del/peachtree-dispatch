export type ClientIssueKind = "api_error" | "api_timeout" | "network_error" | "render_error";

export type ClientIssue = {
  id: string;
  kind: ClientIssueKind;
  message: string;
  path: string;
  at: string;
  details?: Record<string, string | number | boolean | null>;
};

export type PerformanceSnapshot = {
  path: string;
  at: string;
  lcpMs?: number;
  cls?: number;
  inpMs?: number;
  navLoadMs?: number;
  ttfbMs?: number;
};

type ClientIssueInput = {
  kind: ClientIssueKind;
  message: string;
  details?: Record<string, string | number | boolean | null | undefined>;
};

const CLIENT_ISSUES_KEY = "atmospath:client-issues";
const PERF_SNAPSHOT_KEY = "atmospath:perf-snapshot";
const MAX_CLIENT_ISSUES = 20;
let storageAvailability: boolean | null = null;

export function createErrorId() {
  return `AP-${Date.now().toString(36).toUpperCase()}-${Math.random().toString(36).slice(2, 8).toUpperCase()}`;
}

export function reportClientIssue(input: ClientIssueInput) {
  if (!canUseBrowserStorage()) return;
  const issue: ClientIssue = {
    id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    kind: input.kind,
    message: sanitizeText(input.message || "Unexpected client issue"),
    path: window.location.pathname + window.location.search,
    at: new Date().toISOString(),
    details: compactDetails(input.details),
  };
  const issues = [issue, ...readClientIssues()].slice(0, MAX_CLIENT_ISSUES);
  window.sessionStorage.setItem(CLIENT_ISSUES_KEY, JSON.stringify(issues));
  window.dispatchEvent(new CustomEvent<ClientIssue>("atmospath:client-issue", { detail: issue }));
}

export function readClientIssues(): ClientIssue[] {
  if (!canUseBrowserStorage()) return [];
  return readJsonArray<ClientIssue>(CLIENT_ISSUES_KEY).filter(isClientIssue);
}

export function clearClientIssues() {
  if (!canUseBrowserStorage()) return;
  window.sessionStorage.removeItem(CLIENT_ISSUES_KEY);
  window.dispatchEvent(new Event("atmospath:client-issues-cleared"));
}

export function updatePerformanceSnapshot(patch: Partial<PerformanceSnapshot>) {
  if (!canUseBrowserStorage()) return;
  const current = readPerformanceSnapshot();
  const snapshot: PerformanceSnapshot = {
    ...current,
    ...patch,
    path: window.location.pathname + window.location.search,
    at: new Date().toISOString(),
  };
  window.sessionStorage.setItem(PERF_SNAPSHOT_KEY, JSON.stringify(snapshot));
  window.dispatchEvent(new CustomEvent<PerformanceSnapshot>("atmospath:performance", { detail: snapshot }));
}

export function readPerformanceSnapshot(): PerformanceSnapshot | null {
  if (!canUseBrowserStorage()) return null;
  const value = window.sessionStorage.getItem(PERF_SNAPSHOT_KEY);
  if (!value) return null;
  try {
    const parsed = JSON.parse(value) as unknown;
    if (isPerformanceSnapshot(parsed)) return parsed;
  } catch {
    return null;
  }
  return null;
}

function canUseBrowserStorage() {
  if (typeof window === "undefined") return false;
  if (storageAvailability !== null) return storageAvailability;
  try {
    const probe = "atmospath:storage-probe";
    window.sessionStorage.setItem(probe, "1");
    window.sessionStorage.removeItem(probe);
    storageAvailability = true;
  } catch {
    storageAvailability = false;
  }
  return storageAvailability;
}

function readJsonArray<T>(key: string): T[] {
  const value = window.sessionStorage.getItem(key);
  if (!value) return [];
  try {
    const parsed = JSON.parse(value) as unknown;
    return Array.isArray(parsed) ? parsed as T[] : [];
  } catch {
    return [];
  }
}

function sanitizeText(value: string) {
  return value.replace(/\s+/g, " ").slice(0, 240);
}

function compactDetails(details?: ClientIssueInput["details"]) {
  if (!details) return undefined;
  const compacted = Object.entries(details).reduce<Record<string, string | number | boolean | null>>((result, [key, value]) => {
    if (value !== undefined) result[key] = typeof value === "string" ? sanitizeText(value) : value;
    return result;
  }, {});
  return Object.keys(compacted).length ? compacted : undefined;
}

function isClientIssue(value: unknown): value is ClientIssue {
  if (!value || typeof value !== "object") return false;
  const issue = value as ClientIssue;
  return typeof issue.id === "string"
    && typeof issue.kind === "string"
    && typeof issue.message === "string"
    && typeof issue.path === "string"
    && typeof issue.at === "string";
}

function isPerformanceSnapshot(value: unknown): value is PerformanceSnapshot {
  if (!value || typeof value !== "object") return false;
  const snapshot = value as PerformanceSnapshot;
  return typeof snapshot.path === "string" && typeof snapshot.at === "string";
}
