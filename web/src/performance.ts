import { updatePerformanceSnapshot } from "./telemetry";

type LayoutShiftEntry = PerformanceEntry & {
  value?: number;
  hadRecentInput?: boolean;
};

type EventTimingEntry = PerformanceEntry & {
  duration?: number;
};

let started = false;

export function startPerformanceObservers() {
  if (started || typeof window === "undefined" || typeof PerformanceObserver === "undefined") return;
  started = true;
  recordNavigationTiming();
  observeLargestContentfulPaint();
  observeLayoutShift();
  observeInteractionLatency();
}

function recordNavigationTiming() {
  window.setTimeout(() => {
    const navigation = performance.getEntriesByType("navigation")[0] as PerformanceNavigationTiming | undefined;
    if (!navigation) return;
    const loadMs = Math.round(navigation.loadEventEnd > 0 ? navigation.loadEventEnd : navigation.duration);
    if (Number.isFinite(loadMs) && loadMs > 0) updatePerformanceSnapshot({ navLoadMs: loadMs });
  }, 0);
}

function observeLargestContentfulPaint() {
  try {
    const observer = new PerformanceObserver((list) => {
      const latest = list.getEntries().at(-1);
      if (latest) updatePerformanceSnapshot({ lcpMs: Math.round(latest.startTime) });
    });
    observer.observe({ type: "largest-contentful-paint", buffered: true });
  } catch {
    return;
  }
}

function observeLayoutShift() {
  let cls = 0;
  try {
    const observer = new PerformanceObserver((list) => {
      for (const entry of list.getEntries() as LayoutShiftEntry[]) {
        if (!entry.hadRecentInput) cls += entry.value ?? 0;
      }
      updatePerformanceSnapshot({ cls: Number(cls.toFixed(3)) });
    });
    observer.observe({ type: "layout-shift", buffered: true });
  } catch {
    return;
  }
}

function observeInteractionLatency() {
  let maxObservedDuration = 0;
  try {
    const observer = new PerformanceObserver((list) => {
      for (const entry of list.getEntries() as EventTimingEntry[]) {
        maxObservedDuration = Math.max(maxObservedDuration, Math.round(entry.duration ?? 0));
      }
      if (maxObservedDuration > 0) updatePerformanceSnapshot({ inpMs: maxObservedDuration });
    });
    observer.observe({ type: "event", buffered: true, durationThreshold: 40 } as PerformanceObserverInit & { durationThreshold: number });
  } catch {
    return;
  }
}
