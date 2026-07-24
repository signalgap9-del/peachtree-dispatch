import { AlertTriangle, Activity, Gauge, RadioTower, RefreshCw, ShieldCheck } from "lucide-react";
import { useEffect, useMemo, useState, type ReactNode } from "react";

import { ALERT_STREAM_URL } from "./alertStream";
import type { DataStatus, Navigate } from "./App";
import type { NationalRiskOverview, NationalWeatherSnapshot, WeatherRasterManifest } from "./types";
import {
  clearClientIssues,
  readClientIssues,
  readPerformanceSnapshot,
  type ClientIssue,
  type PerformanceSnapshot,
} from "./telemetry";
import type { ResilienceSnapshot } from "./resilience";
import { isSlowNetwork } from "./resilience";
import { useResilienceSnapshot } from "./useResilienceSnapshot";
import { useAlertStream, type AlertStreamState } from "./useAlertStream";

type Props = {
  navigate: Navigate;
  dataStatus: DataStatus;
  national: NationalRiskOverview | null;
  weatherSnapshot: NationalWeatherSnapshot | null;
  weatherRaster: WeatherRasterManifest | null;
};

type SourceRow = {
  label: string;
  status: string;
  detail: string;
};

export function OperationalStatusPage({ navigate, dataStatus, national, weatherSnapshot, weatherRaster }: Props) {
  const [issues, setIssues] = useState<ClientIssue[]>(() => readClientIssues());
  const [performance, setPerformance] = useState<PerformanceSnapshot | null>(() => readPerformanceSnapshot());
  const resilience = useResilienceSnapshot();
  const alertStream = useAlertStream();
  const sourceRows = useMemo(() => buildSourceRows(national, weatherSnapshot, weatherRaster), [national, weatherRaster, weatherSnapshot]);

  useEffect(() => {
    const refresh = () => {
      setIssues(readClientIssues());
      setPerformance(readPerformanceSnapshot());
    };
    const interval = window.setInterval(refresh, 2000);
    window.addEventListener("atmospath:client-issue", refresh);
    window.addEventListener("atmospath:client-issues-cleared", refresh);
    window.addEventListener("atmospath:performance", refresh);
    return () => {
      window.clearInterval(interval);
      window.removeEventListener("atmospath:client-issue", refresh);
      window.removeEventListener("atmospath:client-issues-cleared", refresh);
      window.removeEventListener("atmospath:performance", refresh);
    };
  }, []);

  const healthTone = dataStatus === "ready" ? "low" : dataStatus === "loading" ? "moderate" : "high";
  return (
    <main className="page-shell status-page">
      <header className="page-title">
        <div>
          <h1>Operational status</h1>
          <p>Frontend runtime health, live source status, and performance budget evidence for the current session.</p>
        </div>
        <div>
          <button className="button secondary" onClick={() => navigate("/usage")}>Usage</button>
          <button className="button primary" onClick={() => navigate("/map")}>Open map</button>
        </div>
      </header>

      <section className="status-hero surface">
        <div>
          <span className="eyebrow">Current app state</span>
          <h2>{dataStatus === "ready" ? "All primary live feeds loaded" : dataStatus === "loading" ? "Loading live feeds" : "Running in degraded data mode"}</h2>
          <p>{dataStatus === "degraded" ? "The UI is still usable, but one or more live feeds failed and no fake data is substituted." : "Operators can verify source health and client runtime signals from this page."}</p>
        </div>
        <b className={healthTone}>{dataStatus}</b>
      </section>

      <section className="status-grid">
        <div className="surface status-panel">
          <StatusSectionHeader icon={<ShieldCheck size={20} />} title="Live data sources" meta={`${sourceRows.length} source checks`} />
          <div className="source-list">
            {sourceRows.map((row) => <SourceStatusRow key={row.label} row={row} />)}
          </div>
        </div>
        <div className="surface status-panel">
          <StatusSectionHeader icon={<Gauge size={20} />} title="Performance snapshot" meta={performance?.at ? `Updated ${formatShortTime(performance.at)}` : "Waiting for browser metrics"} />
          {performance ? <PerformanceGrid snapshot={performance} /> : <EmptyOpsState title="No browser metrics yet" detail="Navigate through the app once and this panel will fill from PerformanceObserver data." />}
        </div>
        <div className="surface status-panel resilience-panel">
          <StatusSectionHeader icon={<RefreshCw size={20} />} title="API resiliency" meta={resilience.lastSuccessfulAt ? `Last success ${formatShortTime(resilience.lastSuccessfulAt)}` : "Waiting for API activity"} />
          <ResilienceGrid snapshot={resilience} />
        </div>
        <div className="surface status-panel alert-stream-panel">
          <StatusSectionHeader icon={<RadioTower size={20} />} title="Alert stream" meta={ALERT_STREAM_URL} />
          <AlertStreamGrid stream={alertStream} />
        </div>
      </section>

      <section className="surface status-panel">
        <StatusSectionHeader icon={<AlertTriangle size={20} />} title="Client-side issue log" meta={`${issues.length} recorded this session`}>
          {issues.length > 0 && <button onClick={() => clearClientIssues()}>Clear session log</button>}
        </StatusSectionHeader>
        {issues.length ? (
          <div className="issue-list">
            {issues.map((issue) => (
              <article key={issue.id}>
                <i className={issue.kind === "render_error" ? "high" : "moderate"}>{issue.kind.replace("_", " ")}</i>
                <span><strong>{issue.message}</strong><small>{issue.path} / {formatShortTime(issue.at)}</small></span>
                {issue.details?.requestId && <em>request {issue.details.requestId}</em>}
              </article>
            ))}
          </div>
        ) : <EmptyOpsState title="No client-side issues recorded" detail="API, network, timeout, and render failures are captured here without exposing secrets." />}
      </section>
    </main>
  );
}

function StatusSectionHeader({ icon, title, meta, children }: { icon: ReactNode; title: string; meta: string; children?: ReactNode }) {
  return <header className="status-section-header"><span>{icon}<strong>{title}</strong><small>{meta}</small></span>{children}</header>;
}

function SourceStatusRow({ row }: { row: SourceRow }) {
  const status = row.status.toUpperCase();
  const tone = status.includes("LIVE") || status.includes("READY") ? "low" : status.includes("LOADING") || status.includes("CONFIG") ? "moderate" : "high";
  return <article><Activity size={17} /><span><strong>{row.label}</strong><small>{row.detail}</small></span><b className={tone}>{status}</b></article>;
}

function PerformanceGrid({ snapshot }: { snapshot: PerformanceSnapshot }) {
  const metrics = [
    { label: "LCP", value: formatMs(snapshot.lcpMs), tone: scoreLcp(snapshot.lcpMs), target: "good <= 2,500 ms" },
    { label: "CLS", value: snapshot.cls === undefined ? "pending" : snapshot.cls.toFixed(3), tone: scoreCls(snapshot.cls), target: "good <= 0.100" },
    { label: "INP", value: formatMs(snapshot.inpMs), tone: scoreInp(snapshot.inpMs), target: "good <= 200 ms" },
    { label: "Load", value: formatMs(snapshot.navLoadMs), tone: scoreLoad(snapshot.navLoadMs), target: "local session" },
  ];
  return (
    <div className="performance-grid">
      {metrics.map((metric) => <article key={metric.label}><strong>{metric.label}</strong><b className={metric.tone}>{metric.value}</b><small>{metric.target}</small></article>)}
    </div>
  );
}

function ResilienceGrid({ snapshot }: { snapshot: ResilienceSnapshot }) {
  const metrics = [
    { label: "Network", value: snapshot.online ? networkLabel(snapshot) : "offline", tone: snapshot.online ? isSlowNetwork(snapshot) ? "moderate" : "low" : "high", target: "browser connectivity" },
    { label: "Retries this session", value: `${snapshot.retryCount}`, tone: snapshot.retryCount > 0 ? "moderate" : "low", target: "safe reads only" },
    { label: "Stale fallbacks", value: `${snapshot.staleFallbackCount}`, tone: snapshot.staleFallbackCount > 0 ? "moderate" : "low", target: "public risk cache" },
    { label: "Last stale key", value: snapshot.lastStaleKey ?? "none", tone: snapshot.lastStaleKey ? "moderate" : "low", target: snapshot.lastStaleCachedAt ? `cached ${formatShortTime(snapshot.lastStaleCachedAt)}` : "no fallback used" },
  ];
  return (
    <div className="performance-grid resilience-grid">
      {metrics.map((metric) => <article key={metric.label}><strong>{metric.label}</strong><b className={metric.tone}>{metric.value}</b><small>{metric.target}</small></article>)}
    </div>
  );
}

function AlertStreamGrid({ stream }: { stream: AlertStreamState }) {
  const metrics = [
    { label: "Connection", value: stream.status, tone: stream.status === "connected" ? "low" : stream.status === "error" ? "high" : "moderate", target: "SSE keepalive every 15s" },
    { label: "Last event", value: stream.lastEventAt ? formatShortTime(stream.lastEventAt) : "pending", tone: stream.lastEventAt ? "low" : "moderate", target: "national_risk or heartbeat" },
    { label: "Reconnect attempts", value: `${stream.reconnectAttempts}`, tone: stream.reconnectAttempts > 0 ? "moderate" : "low", target: "2s backoff, capped at 60s" },
    { label: "Severe alerts", value: stream.lastAlert ? `${stream.lastAlert.severe_alerts}` : "pending", tone: stream.lastAlert && stream.lastAlert.severe_alerts > 0 ? "high" : "low", target: stream.lastAlert ? `${stream.lastAlert.level} national risk` : "waiting for first payload" },
  ];
  return (
    <div className="performance-grid alert-stream-grid">
      {metrics.map((metric) => <article key={metric.label}><strong>{metric.label}</strong><b className={metric.tone}>{metric.value}</b><small>{metric.target}</small></article>)}
    </div>
  );
}

function EmptyOpsState({ title, detail }: { title: string; detail: string }) {
  return <div className="ops-empty"><Gauge size={22} /><strong>{title}</strong><small>{detail}</small></div>;
}

function buildSourceRows(national: NationalRiskOverview | null, snapshot: NationalWeatherSnapshot | null, raster: WeatherRasterManifest | null): SourceRow[] {
  return [
    {
      label: "NWS alerts",
      status: national?.source_status?.nws_alerts ?? "loading",
      detail: national ? `${national.active_alerts} active alerts / ${national.severe_alerts} severe` : "Awaiting national alert summary",
    },
    {
      label: "Weather snapshot",
      status: snapshot?.source_status?.nws ?? "loading",
      detail: snapshot ? `${snapshot.points.length} monitored points / ${Math.round(snapshot.coverage * 100)}% coverage` : "Awaiting live monitored points",
    },
    {
      label: "HRRR/MRMS raster artifact",
      status: raster ? "ready" : "loading",
      detail: raster ? `${raster.point_count} raster source points / ${Math.round(raster.coverage * 100)}% coverage` : "Awaiting weather raster manifest",
    },
    {
      label: "Build mode",
      status: import.meta.env.MODE,
      detail: `Vite mode ${import.meta.env.MODE}; release ${import.meta.env.VITE_GIT_SHA ?? "local"}`,
    },
  ];
}

function formatMs(value?: number) {
  return value === undefined ? "pending" : `${value.toLocaleString()} ms`;
}

function formatShortTime(value: string) {
  return new Intl.DateTimeFormat("en-US", { hour: "numeric", minute: "2-digit", second: "2-digit" }).format(new Date(value));
}

function networkLabel(snapshot: ResilienceSnapshot) {
  if (snapshot.saveData) return "save-data";
  return snapshot.effectiveType ?? "online";
}

function scoreLcp(value?: number) {
  if (value === undefined) return "moderate";
  if (value <= 2500) return "low";
  if (value <= 4000) return "moderate";
  return "high";
}

function scoreCls(value?: number) {
  if (value === undefined) return "moderate";
  if (value <= 0.1) return "low";
  if (value <= 0.25) return "moderate";
  return "high";
}

function scoreInp(value?: number) {
  if (value === undefined) return "moderate";
  if (value <= 200) return "low";
  if (value <= 500) return "moderate";
  return "high";
}

function scoreLoad(value?: number) {
  if (value === undefined) return "moderate";
  return value <= 3500 ? "low" : "moderate";
}
