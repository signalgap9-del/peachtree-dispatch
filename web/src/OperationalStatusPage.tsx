import { Activity, AlertTriangle, ChevronRight, Gauge, RadioTower, RefreshCw, ShieldCheck, Sparkles, Wrench } from "lucide-react";
import { useEffect, useMemo, useState, type ReactNode } from "react";

import { ALERT_STREAM_URL } from "./alertStream";
import type { DataStatus, Navigate } from "./App";
import { getLlmLatencyStats, getLlmServiceStatus, LLM_STATUS_URL } from "./llmApi";
import type { LlmStatus, NationalRiskOverview, NationalWeatherSnapshot, WeatherRasterManifest } from "./types";
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

type StatusRow = {
  label: string;
  detail: string;
  status: string;
  tone: string;
};

type GlanceMetric = {
  label: string;
  value: string;
  tone: string;
  small: string;
};

export function OperationalStatusPage({ navigate, dataStatus, national, weatherSnapshot, weatherRaster }: Props) {
  const [issues, setIssues] = useState<ClientIssue[]>(() => readClientIssues());
  const [performance, setPerformance] = useState<PerformanceSnapshot | null>(() => readPerformanceSnapshot());
  const [llmStatus, setLlmStatus] = useState<LlmStatus | null>(null);
  const [llmChecked, setLlmChecked] = useState(false);
  const resilience = useResilienceSnapshot();
  const alertStream = useAlertStream();
  const rows = useMemo(
    () => buildStatusRows(dataStatus, national, weatherSnapshot, weatherRaster, alertStream, llmStatus, llmChecked, resilience),
    [alertStream, dataStatus, llmChecked, llmStatus, national, resilience, weatherRaster, weatherSnapshot],
  );
  const glance = useMemo(() => buildGlance(national, weatherSnapshot), [national, weatherSnapshot]);

  useEffect(() => {
    const refresh = () => {
      setIssues(readClientIssues());
      setPerformance(readPerformanceSnapshot());
    };
    const interval = window.setInterval(refresh, 2000);
    window.addEventListener("freightscaler:client-issue", refresh);
    window.addEventListener("freightscaler:client-issues-cleared", refresh);
    window.addEventListener("freightscaler:performance", refresh);
    return () => {
      window.clearInterval(interval);
      window.removeEventListener("freightscaler:client-issue", refresh);
      window.removeEventListener("freightscaler:client-issues-cleared", refresh);
      window.removeEventListener("freightscaler:performance", refresh);
    };
  }, []);

  useEffect(() => {
    let active = true;
    void getLlmServiceStatus().then((value) => {
      if (!active) return;
      setLlmStatus(value);
      setLlmChecked(true);
    });
    return () => {
      active = false;
    };
  }, []);

  const healthTone = dataStatus === "ready" ? "low" : dataStatus === "loading" ? "moderate" : "high";
  const healthLabel = dataStatus === "ready" ? "All good" : dataStatus === "loading" ? "Checking" : "Partial";
  return (
    <main className="page-shell status-page">
      <header className="page-title">
        <div>
          <h1>Is everything working?</h1>
          <p>Live status of the weather data, alerts, and tools FreightScaler uses to plan your routes.</p>
        </div>
        <div>
          <button className="button secondary" onClick={() => navigate("/usage")}>Plan &amp; Usage</button>
          <button className="button primary" onClick={() => navigate("/map")}>Open map</button>
        </div>
      </header>

      <section className="status-hero surface">
        <div>
          <span className="eyebrow">Right now</span>
          <h2>{dataStatus === "ready" ? "Everything is working" : dataStatus === "loading" ? "Checking live data" : "Some data is delayed"}</h2>
          <p>{dataStatus === "degraded" ? "One or more live feeds didn't respond. We're showing the last known data instead of guessing." : "Weather, alerts, and route planning are all reporting in."}</p>
        </div>
        <b className={healthTone}>{healthLabel}</b>
      </section>

      <section className="status-grid">
        <div className="surface status-panel">
          <StatusSectionHeader icon={<ShieldCheck size={20} />} title="Live status" meta={`${rows.length} checks`} />
          <div className="source-list">
            {rows.map((row) => <StatusRowView key={row.label} row={row} />)}
          </div>
        </div>
        <div className="surface status-panel">
          <StatusSectionHeader icon={<Activity size={20} />} title="At a glance" meta={national ? `Updated ${formatAgo(national.generated_at) ?? formatShortTime(national.generated_at)}` : "Waiting for data"} />
          <div className="performance-grid">
            {glance.map((metric) => <article key={metric.label}><strong>{metric.label}</strong><b className={metric.tone}>{metric.value}</b><small>{metric.small}</small></article>)}
          </div>
        </div>
      </section>

      <details className="status-technical">
        <summary>
          <Wrench size={18} />
          <span><strong>Technical details</strong><small>Connection metrics, performance, and session logs</small></span>
          <ChevronRight size={16} className="chev" />
        </summary>
        <div className="status-grid">
          <div className="surface status-panel">
            <StatusSectionHeader icon={<Gauge size={20} />} title="Performance snapshot" meta={performance?.at ? `Updated ${formatShortTime(performance.at)}` : "Waiting for browser metrics"} />
            {performance ? <PerformanceGrid snapshot={performance} /> : <EmptyOpsState title="No browser metrics yet" detail="Use the app for a minute and speed metrics will show up here." />}
          </div>
          <div className="surface status-panel resilience-panel">
            <StatusSectionHeader icon={<RefreshCw size={20} />} title="Connection health" meta={resilience.lastSuccessfulAt ? `Last successful request ${formatShortTime(resilience.lastSuccessfulAt)}` : "Waiting for API activity"} />
            <ConnectionGrid snapshot={resilience} />
          </div>
          <div className="surface status-panel alert-stream-panel">
            <StatusSectionHeader icon={<RadioTower size={20} />} title="Alert stream" meta={ALERT_STREAM_URL} />
            <AlertStreamGrid stream={alertStream} />
          </div>
          <LlmServicePanel status={llmStatus} checked={llmChecked} />
        </div>
        <section className="surface status-panel technical-issue-log">
          <StatusSectionHeader icon={<AlertTriangle size={20} />} title="Session issue log" meta={`${issues.length} recorded this session`}>
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
          ) : <EmptyOpsState title="No issues recorded this session" detail="Request, network, timeout, and rendering problems show up here." />}
        </section>
      </details>
    </main>
  );
}

function StatusSectionHeader({ icon, title, meta, children }: { icon: ReactNode; title: string; meta: string; children?: ReactNode }) {
  return <header className="status-section-header"><span>{icon}<strong>{title}</strong><small>{meta}</small></span>{children}</header>;
}

function StatusRowView({ row }: { row: StatusRow }) {
  return <article><Activity size={17} /><span><strong>{row.label}</strong><small>{row.detail}</small></span><b className={row.tone}>{row.status}</b></article>;
}

function PerformanceGrid({ snapshot }: { snapshot: PerformanceSnapshot }) {
  const metrics = [
    { label: "LCP", value: formatMs(snapshot.lcpMs), tone: scoreLcp(snapshot.lcpMs), target: "good <= 2,500 ms" },
    { label: "CLS", value: snapshot.cls === undefined ? "pending" : snapshot.cls.toFixed(3), tone: scoreCls(snapshot.cls), target: "good <= 0.100" },
    { label: "INP", value: formatMs(snapshot.inpMs), tone: scoreInp(snapshot.inpMs), target: "good <= 200 ms" },
    { label: "TTFB", value: formatMs(snapshot.ttfbMs), tone: scoreTtfb(snapshot.ttfbMs), target: "good <= 800 ms" },
    { label: "Load", value: formatMs(snapshot.navLoadMs), tone: scoreLoad(snapshot.navLoadMs), target: "local session" },
  ];
  return (
    <div className="performance-grid">
      {metrics.map((metric) => <article key={metric.label}><strong>{metric.label}</strong><b className={metric.tone}>{metric.value}</b><small>{metric.target}</small></article>)}
    </div>
  );
}

function ConnectionGrid({ snapshot }: { snapshot: ResilienceSnapshot }) {
  const metrics = [
    { label: "Network", value: snapshot.online ? networkLabel(snapshot) : "offline", tone: snapshot.online ? isSlowNetwork(snapshot) ? "moderate" : "low" : "high", target: "browser connectivity" },
    { label: "Retries this session", value: `${snapshot.retryCount}`, tone: snapshot.retryCount > 0 ? "moderate" : "low", target: "safe reads only" },
    { label: "Times saved data was shown", value: `${snapshot.staleFallbackCount}`, tone: snapshot.staleFallbackCount > 0 ? "moderate" : "low", target: "cached public risk data" },
    { label: "Last saved data used", value: snapshot.lastStaleKey ?? "none", tone: snapshot.lastStaleKey ? "moderate" : "low", target: snapshot.lastStaleCachedAt ? `cached ${formatShortTime(snapshot.lastStaleCachedAt)}` : "none used yet" },
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
    { label: "Reconnects", value: `${stream.reconnectAttempts}`, tone: stream.reconnectAttempts > 0 ? "moderate" : "low", target: "2s backoff, capped at 60s" },
    { label: "Severe alerts", value: stream.lastAlert ? `${stream.lastAlert.severe_alerts}` : "pending", tone: stream.lastAlert && stream.lastAlert.severe_alerts > 0 ? "high" : "low", target: stream.lastAlert ? `${stream.lastAlert.level} national risk` : "waiting for first payload" },
  ];
  return (
    <div className="performance-grid alert-stream-grid">
      {metrics.map((metric) => <article key={metric.label}><strong>{metric.label}</strong><b className={metric.tone}>{metric.value}</b><small>{metric.target}</small></article>)}
    </div>
  );
}

function LlmServicePanel({ status, checked }: { status: LlmStatus | null; checked: boolean }) {
  const latency = getLlmLatencyStats();
  const usedPct = status && status.dailyTokenBudget > 0
    ? Math.min(100, Math.round((status.dailyTokensUsed / status.dailyTokenBudget) * 100))
    : 0;

  return (
    <div className="surface status-panel llm-status-panel">
      <StatusSectionHeader icon={<Sparkles size={20} />} title="AI assistant service" meta={LLM_STATUS_URL} />
      {!checked ? (
        <EmptyOpsState title="Checking the assistant service" detail="Pinging the AI assistant to see if it's available." />
      ) : !status ? (
        <EmptyOpsState title="AI assistant is off" detail="The assistant is switched off for this server. Chat, summaries, and suggestions are paused until it's back on." />
      ) : (
        <>
          <div className="performance-grid llm-grid">
            <article><strong>Connection</strong><b className={status.enabled ? "low" : "high"}>{status.enabled ? "enabled" : "disabled"}</b><small>freightscaler.llm.enabled</small></article>
            <article><strong>Model</strong><b className="low">{status.model || "unknown"}</b><small>via LiteLLM proxy</small></article>
            <article><strong>Avg response start</strong><b className={latency && latency.averageMs <= 2500 ? "low" : "moderate"}>{latency ? `${latency.averageMs.toLocaleString()} ms` : "no samples"}</b><small>{latency ? `${latency.samples} chat sample(s) this session` : "measured in your browser during chats"}</small></article>
            <article><strong>Search index</strong><b className={status.ragEnabled ? "low" : "moderate"}>{status.ragEnabled ? "up" : "down"}</b><small>{status.ragVectorCount !== undefined ? `${status.ragVectorCount.toLocaleString()} vectors indexed` : "search unavailable"}</small></article>
          </div>
          <div className="token-budget">
            <span>Daily AI usage</span>
            <i><b className={usedPct >= 90 ? "high" : ""} style={{ width: `${usedPct}%` }} /></i>
            <em>{status.dailyTokensUsed.toLocaleString()} / {status.dailyTokenBudget.toLocaleString()}</em>
          </div>
        </>
      )}
    </div>
  );
}

function EmptyOpsState({ title, detail }: { title: string; detail: string }) {
  return <div className="ops-empty"><Gauge size={22} /><strong>{title}</strong><small>{detail}</small></div>;
}

function buildStatusRows(
  dataStatus: DataStatus,
  national: NationalRiskOverview | null,
  snapshot: NationalWeatherSnapshot | null,
  raster: WeatherRasterManifest | null,
  stream: AlertStreamState,
  llmStatus: LlmStatus | null,
  llmChecked: boolean,
  resilience: ResilienceSnapshot,
): StatusRow[] {
  const alertsAgo = formatAgo(national?.generated_at);
  const alertsFromCache = resilience.lastStaleKey === "risk:national";
  const weatherAgo = formatAgo(snapshot?.generated_at);
  const weatherFromCache = resilience.lastStaleKey === "risk:weather-snapshot";
  const rasterFromCache = resilience.lastStaleKey === "risk:weather-raster";
  const planStatus = dataStatus === "ready"
    ? { label: "Working", tone: "low" }
    : dataStatus === "loading"
      ? { label: "Starting up...", tone: "moderate" }
      : { label: "Working with delays", tone: "moderate" };
  const assistantStatus = !llmChecked
    ? { label: "Checking...", tone: "moderate" }
    : llmStatus?.enabled
      ? { label: "Available", tone: "low" }
      : { label: "Unavailable", tone: "high" };
  const streamStatus = stream.status === "connected"
    ? { label: "Streaming live", tone: "low" }
    : stream.status === "error"
      ? { label: "Reconnecting...", tone: "high" }
      : { label: "Connecting...", tone: "moderate" };
  return [
    {
      label: "Weather alerts",
      status: national ? (alertsFromCache ? "Last known data" : "Up to date") : "Connecting...",
      tone: national ? (alertsFromCache ? "moderate" : "low") : "moderate",
      detail: national
        ? `${national.active_alerts} active / ${national.severe_alerts} severe${alertsAgo ? ` · updated ${alertsAgo}` : ""}`
        : "Waiting for the latest alerts",
    },
    {
      label: "Weather data",
      status: snapshot ? (weatherFromCache ? "Last known data" : "Up to date") : "Connecting...",
      tone: snapshot ? (weatherFromCache ? "moderate" : "low") : "moderate",
      detail: snapshot
        ? `${snapshot.points.length.toLocaleString()} monitored points${weatherAgo ? ` · updated ${weatherAgo}` : ""}`
        : "Waiting for live weather",
    },
    {
      label: "Radar and precipitation",
      status: raster ? (rasterFromCache ? "Last known data" : "Up to date") : "Connecting...",
      tone: raster ? (rasterFromCache ? "moderate" : "low") : "moderate",
      detail: raster ? `${Math.round(raster.coverage * 100)}% of the country covered` : "Waiting for radar coverage",
    },
    {
      label: "Route planning",
      status: planStatus.label,
      tone: planStatus.tone,
      detail: "Directions and route comparisons",
    },
    {
      label: "AI assistant",
      status: assistantStatus.label,
      tone: assistantStatus.tone,
      detail: llmStatus?.enabled ? "Chat answers and alert summaries" : "Chat and summaries are paused",
    },
    {
      label: "Live alert updates",
      status: streamStatus.label,
      tone: streamStatus.tone,
      detail: stream.lastEventAt ? `Last update ${formatShortTime(stream.lastEventAt)}` : "Waiting for updates",
    },
  ];
}

function buildGlance(national: NationalRiskOverview | null, snapshot: NationalWeatherSnapshot | null): GlanceMetric[] {
  return [
    {
      label: "Active alerts",
      value: national ? `${national.active_alerts}` : "--",
      tone: national && national.active_alerts > 0 ? "moderate" : "low",
      small: "nationwide",
    },
    {
      label: "Severe alerts",
      value: national ? `${national.severe_alerts}` : "--",
      tone: national && national.severe_alerts > 0 ? "high" : "low",
      small: "watch and warning areas",
    },
    {
      label: "Monitored points",
      value: snapshot ? snapshot.points.length.toLocaleString() : "--",
      tone: "low",
      small: "live weather locations",
    },
    {
      label: "Data updated",
      value: formatAgo(national?.generated_at) ?? "pending",
      tone: "low",
      small: "national risk summary",
    },
  ];
}

function formatMs(value?: number) {
  return value === undefined ? "pending" : `${value.toLocaleString()} ms`;
}

function formatAgo(value?: string) {
  if (!value) return null;
  const ms = Date.now() - new Date(value).getTime();
  if (Number.isNaN(ms) || ms < 0) return null;
  const minutes = Math.floor(ms / 60000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes} min ago`;
  return `${Math.floor(minutes / 60)} hr ago`;
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

function scoreTtfb(value?: number) {
  if (value === undefined) return "moderate";
  if (value <= 800) return "low";
  if (value <= 1800) return "moderate";
  return "high";
}

function scoreLoad(value?: number) {
  if (value === undefined) return "moderate";
  return value <= 3500 ? "low" : "moderate";
}
