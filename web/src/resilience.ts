export type ResilienceSnapshot = {
  online: boolean;
  effectiveType?: string;
  saveData?: boolean;
  retryCount: number;
  staleFallbackCount: number;
  consecutiveFailures: number;
  lastSuccessfulAt?: string;
  lastSuccessPath?: string;
  lastRetryAt?: string;
  lastRetryPath?: string;
  lastRetryStatus?: number;
  lastStaleAt?: string;
  lastStaleKey?: string;
  lastStaleCachedAt?: string;
  lastFailureAt?: string;
  lastFailurePath?: string;
  lastFailureKind?: string;
};

export type StaleCacheHit<T> = {
  data: T;
  cachedAt: string;
  ageMs: number;
};

type ResilienceListener = (snapshot: ResilienceSnapshot) => void;
type NetworkInformationLike = {
  effectiveType?: string;
  saveData?: boolean;
  addEventListener?: (type: "change", listener: () => void) => void;
  removeEventListener?: (type: "change", listener: () => void) => void;
};

const RESILIENCE_SNAPSHOT_KEY = "freightscaler:resilience-snapshot";
const RESILIENCE_CACHE_PREFIX = "freightscaler:resilience-cache:";
const RESILIENCE_CACHE_VERSION = 1;
const RESILIENCE_CACHE_MAX_BYTES = 512_000;

let sessionStorageAvailable: boolean | null = null;
let localStorageAvailable: boolean | null = null;
let listenersInstalled = false;
const listeners = new Set<ResilienceListener>();

let snapshot: ResilienceSnapshot = {
  online: readOnlineStatus(),
  ...readConnectionStatus(),
  retryCount: 0,
  staleFallbackCount: 0,
  consecutiveFailures: 0,
  ...readStoredSnapshot(),
};

export function getResilienceSnapshot(): ResilienceSnapshot {
  ensureNetworkListeners();
  return { ...snapshot };
}

export function subscribeResilienceSnapshot(listener: ResilienceListener) {
  ensureNetworkListeners();
  listeners.add(listener);
  listener(getResilienceSnapshot());
  return () => {
    listeners.delete(listener);
  };
}

export function recordApiSuccess(path: string) {
  updateSnapshot({
    online: readOnlineStatus(),
    ...readConnectionStatus(),
    consecutiveFailures: 0,
    lastSuccessfulAt: new Date().toISOString(),
    lastSuccessPath: sanitizePath(path),
  });
}

export function recordApiRetry(input: { path: string; status?: number }) {
  updateSnapshot({
    online: readOnlineStatus(),
    ...readConnectionStatus(),
    retryCount: snapshot.retryCount + 1,
    lastRetryAt: new Date().toISOString(),
    lastRetryPath: sanitizePath(input.path),
    lastRetryStatus: input.status,
  });
}

export function recordApiFailure(input: { path: string; kind: string }) {
  updateSnapshot({
    online: readOnlineStatus(),
    ...readConnectionStatus(),
    consecutiveFailures: snapshot.consecutiveFailures + 1,
    lastFailureAt: new Date().toISOString(),
    lastFailurePath: sanitizePath(input.path),
    lastFailureKind: input.kind.slice(0, 80),
  });
}

export function recordStaleFallback(input: { path: string; cacheKey: string; cachedAt: string }) {
  updateSnapshot({
    online: readOnlineStatus(),
    ...readConnectionStatus(),
    staleFallbackCount: snapshot.staleFallbackCount + 1,
    consecutiveFailures: 0,
    lastStaleAt: new Date().toISOString(),
    lastStaleKey: input.cacheKey,
    lastStaleCachedAt: input.cachedAt,
    lastFailurePath: sanitizePath(input.path),
  });
}

export function isSlowNetwork(snapshotToScore = snapshot) {
  const type = snapshotToScore.effectiveType?.toLowerCase();
  return Boolean(snapshotToScore.saveData || type === "slow-2g" || type === "2g");
}

export function writeStaleCache<T>(key: string, data: T) {
  if (!canUseLocalStorage()) return;
  const record = {
    version: RESILIENCE_CACHE_VERSION,
    cachedAt: new Date().toISOString(),
    data,
  };
  const serialized = JSON.stringify(record);
  if (serialized.length > RESILIENCE_CACHE_MAX_BYTES) return;
  window.localStorage.setItem(cacheStorageKey(key), serialized);
}

export function readStaleCache<T>(key: string, maxAgeMs: number): StaleCacheHit<T> | null {
  if (!canUseLocalStorage()) return null;
  const serialized = window.localStorage.getItem(cacheStorageKey(key));
  if (!serialized) return null;
  try {
    const parsed = JSON.parse(serialized) as { version?: number; cachedAt?: unknown; data?: T };
    if (parsed.version !== RESILIENCE_CACHE_VERSION || typeof parsed.cachedAt !== "string") return null;
    const cachedAtMs = Date.parse(parsed.cachedAt);
    if (!Number.isFinite(cachedAtMs)) return null;
    const ageMs = Math.max(0, Date.now() - cachedAtMs);
    if (ageMs > maxAgeMs) return null;
    return { data: parsed.data as T, cachedAt: parsed.cachedAt, ageMs };
  } catch {
    return null;
  }
}

function updateSnapshot(patch: Partial<ResilienceSnapshot>) {
  snapshot = {
    ...snapshot,
    ...patch,
    online: patch.online ?? readOnlineStatus(),
  };
  persistSnapshot(snapshot);
  for (const listener of listeners) listener(getResilienceSnapshot());
  if (typeof window !== "undefined") {
    window.dispatchEvent(new CustomEvent<ResilienceSnapshot>("freightscaler:resilience", { detail: getResilienceSnapshot() }));
  }
}

function ensureNetworkListeners() {
  if (listenersInstalled || typeof window === "undefined") return;
  listenersInstalled = true;
  const refresh = () => updateSnapshot({ online: readOnlineStatus(), ...readConnectionStatus() });
  window.addEventListener("online", refresh);
  window.addEventListener("offline", refresh);
  readConnection()?.addEventListener?.("change", refresh);
  refresh();
}

function readStoredSnapshot(): Partial<ResilienceSnapshot> {
  if (!canUseSessionStorage()) return {};
  try {
    const value = window.sessionStorage.getItem(RESILIENCE_SNAPSHOT_KEY);
    if (!value) return {};
    const parsed = JSON.parse(value) as Partial<ResilienceSnapshot>;
    return {
      retryCount: Number.isFinite(parsed.retryCount) ? parsed.retryCount : 0,
      staleFallbackCount: Number.isFinite(parsed.staleFallbackCount) ? parsed.staleFallbackCount : 0,
      consecutiveFailures: Number.isFinite(parsed.consecutiveFailures) ? parsed.consecutiveFailures : 0,
      lastSuccessfulAt: typeof parsed.lastSuccessfulAt === "string" ? parsed.lastSuccessfulAt : undefined,
      lastSuccessPath: typeof parsed.lastSuccessPath === "string" ? parsed.lastSuccessPath : undefined,
      lastRetryAt: typeof parsed.lastRetryAt === "string" ? parsed.lastRetryAt : undefined,
      lastRetryPath: typeof parsed.lastRetryPath === "string" ? parsed.lastRetryPath : undefined,
      lastRetryStatus: Number.isFinite(parsed.lastRetryStatus) ? parsed.lastRetryStatus : undefined,
      lastStaleAt: typeof parsed.lastStaleAt === "string" ? parsed.lastStaleAt : undefined,
      lastStaleKey: typeof parsed.lastStaleKey === "string" ? parsed.lastStaleKey : undefined,
      lastStaleCachedAt: typeof parsed.lastStaleCachedAt === "string" ? parsed.lastStaleCachedAt : undefined,
      lastFailureAt: typeof parsed.lastFailureAt === "string" ? parsed.lastFailureAt : undefined,
      lastFailurePath: typeof parsed.lastFailurePath === "string" ? parsed.lastFailurePath : undefined,
      lastFailureKind: typeof parsed.lastFailureKind === "string" ? parsed.lastFailureKind : undefined,
    };
  } catch {
    return {};
  }
}

function persistSnapshot(value: ResilienceSnapshot) {
  if (!canUseSessionStorage()) return;
  window.sessionStorage.setItem(RESILIENCE_SNAPSHOT_KEY, JSON.stringify(value));
}

function readOnlineStatus() {
  if (typeof navigator === "undefined" || typeof navigator.onLine !== "boolean") return true;
  return navigator.onLine;
}

function readConnectionStatus() {
  const connection = readConnection();
  return {
    effectiveType: connection?.effectiveType,
    saveData: connection?.saveData,
  };
}

function readConnection(): NetworkInformationLike | undefined {
  if (typeof navigator === "undefined") return undefined;
  return (navigator as Navigator & { connection?: NetworkInformationLike }).connection;
}

function cacheStorageKey(key: string) {
  return `${RESILIENCE_CACHE_PREFIX}${key}`;
}

function sanitizePath(path: string) {
  return path.replace(/\?.*$/, "").slice(0, 120);
}

function canUseSessionStorage() {
  if (typeof window === "undefined") return false;
  if (sessionStorageAvailable !== null) return sessionStorageAvailable;
  try {
    const probe = "freightscaler:resilience-session-probe";
    window.sessionStorage.setItem(probe, "1");
    window.sessionStorage.removeItem(probe);
    sessionStorageAvailable = true;
  } catch {
    sessionStorageAvailable = false;
  }
  return sessionStorageAvailable;
}

function canUseLocalStorage() {
  if (typeof window === "undefined") return false;
  if (localStorageAvailable !== null) return localStorageAvailable;
  try {
    const probe = "freightscaler:resilience-local-probe";
    window.localStorage.setItem(probe, "1");
    window.localStorage.removeItem(probe);
    localStorageAvailable = true;
  } catch {
    localStorageAvailable = false;
  }
  return localStorageAvailable;
}
