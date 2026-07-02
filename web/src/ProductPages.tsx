import {
  AlertTriangle,
  ArrowRight,
  Bell,
  Bookmark,
  ChevronRight,
  CloudRain,
  CloudSun,
  Droplets,
  Filter,
  Folder,
  Gauge,
  Layers3,
  MapPin,
  Navigation,
  Plus,
  Search,
  ShieldCheck,
  SlidersHorizontal,
  Wind,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";

import type { DataStatus, Navigate } from "./App";
import { api } from "./api";
import { currentUser, googleAuthConfigured, login, loginWithGoogle } from "./auth";
import { useI18n } from "./i18n";
import { places, riskLevel } from "./mockData";
import type {
  LocationRisk,
  NationalRiskOverview,
  NationalWeatherSnapshot,
  RiskAlert,
  SavedPlaceRecord,
  WeatherRisk,
} from "./types";
import { notify } from "./ui";

type LiveProps = {
  navigate: Navigate;
  national: NationalRiskOverview | null;
  dataStatus: DataStatus;
};

export function HomePage({
  navigate,
  national,
  weatherSnapshot,
  dataStatus,
}: LiveProps & { weatherSnapshot: NationalWeatherSnapshot | null }) {
  const { t } = useI18n();
  const [query, setQuery] = useState("");
  const submit = () => navigate(query.trim() ? `/map?search=${encodeURIComponent(query.trim())}` : "/directions");
  const highRisk = topWeatherPoints(weatherSnapshot, 4);
  const alerts = topAlerts(national, 3);

  return (
    <main className="page-shell home-page">
      <section className="welcome-row">
        <div>
          <span className="eyebrow">{t("home.eyebrow")}</span>
          <h1>{t("home.title")} <CloudSun size={28} /></h1>
          <p>{t("home.subtitle")}</p>
        </div>
      </section>
      <section className="home-actions">
        <label className="hero-search">
          <Search size={20} />
          <input value={query} onChange={(event) => setQuery(event.target.value)} onKeyDown={(event) => event.key === "Enter" && submit()} placeholder={t("home.search")} />
          <SlidersHorizontal size={18} />
        </label>
        <QuickAction icon={<Navigation />} title={t("home.planRoute")} subtitle={t("home.planRouteSubtitle")} onClick={() => navigate("/directions")} />
        <QuickAction icon={<Layers3 />} title={t("home.exploreRisk")} subtitle={t("home.exploreRiskSubtitle")} onClick={() => navigate("/map")} />
        <QuickAction icon={<Bookmark />} title={t("home.savedPlaces")} subtitle={t("home.savedPlacesSubtitle")} onClick={() => navigate("/saved")} />
      </section>
      <DataNotice status={dataStatus} hasData={Boolean(national || weatherSnapshot)} />
      <section className="home-grid">
        <div className="surface outlook-card">
          <SectionHeader title={t("home.nationalOutlook")} meta={national ? `Updated ${formatTime(national.generated_at)}` : "Waiting for NWS"} action={t("home.viewMap")} onAction={() => navigate("/map")} />
          <RiskMapVisual national={national} weatherSnapshot={weatherSnapshot} />
        </div>
        <div className="surface matters-card">
          <SectionHeader title={t("home.activeHazards")} action={t("home.viewAlerts")} onAction={() => navigate("/alerts")} />
          {alerts.length ? alerts.map((alert, index) => <AlertStory key={alert.alert_id} alert={alert} index={index} onClick={() => navigate("/alerts")} />) : <EmptyState title="No live alerts loaded" detail="The service will populate this panel from the National Weather Service feed." />}
        </div>
      </section>
      <section className="surface live-priority">
        <SectionHeader title={t("home.highestRisk")} meta={`${weatherSnapshot?.points.length ?? 0} live monitored points`} action={t("home.openDashboard")} onAction={() => navigate("/dashboard")} />
        <div className="recent-grid">
          {highRisk.length ? highRisk.map((point) => (
            <button key={point.id} onClick={() => navigate(`/map?search=${encodeURIComponent(point.city)}`)}>
              <MapThumb seed={point.id} />
              <span><strong>{point.city}</strong><i className={riskLevel(point.risk_score)}>{riskLevelLabel(point.risk_score)}</i></span>
              <small>{weatherSummary(point)}</small>
            </button>
          )) : <EmptyState title="Weather grid is loading" detail="Live HRRR/MRMS and NWS-derived points will appear here." />}
        </div>
      </section>
    </main>
  );
}

export function DashboardPage({
  navigate,
  national,
  weatherSnapshot,
  dataStatus,
}: LiveProps & { weatherSnapshot: NationalWeatherSnapshot | null }) {
  const { t } = useI18n();
  const rows = useMemo(() => topWeatherPoints(weatherSnapshot, 8), [weatherSnapshot]);
  const alerts = topAlerts(national, 5);
  return (
    <main className="page-shell dashboard-page">
      <PageTitle title={t("dashboard.title")} subtitle={t("dashboard.subtitle")}>
        <button className="button secondary" onClick={() => navigate("/saved")}>{t("dashboard.manageSaved")}</button>
        <button className="button primary" onClick={() => navigate("/directions")}>{t("dashboard.planRoute")} <Plus size={16} /></button>
      </PageTitle>
      <DataNotice status={dataStatus} hasData={Boolean(national || weatherSnapshot)} />
      <section className="dashboard-top">
        <div className="surface risk-matrix">
          <SectionHeader title="Highest current risk" meta={`Coverage ${Math.round((weatherSnapshot?.coverage ?? 0) * 100)}%`} />
          <div className="matrix-head"><span /><span>Risk</span><span>Rain</span><span>Wind</span><span>Temp</span></div>
          {rows.map((point) => (
            <button key={point.id} onClick={() => navigate(`/map?search=${encodeURIComponent(point.city)}`)}>
              <span><strong>{point.city}</strong><small>{point.source ?? "NOAA/NWS"}</small></span>
              <i className={riskLevel(point.risk_score)}>{point.risk_score}</i>
              <i>{Math.round(point.precipitation_probability)}%</i>
              <i>{Math.round(point.wind_speed_mph)}</i>
              <i>{Math.round(point.temperature_f)}°</i>
            </button>
          ))}
          {!rows.length && <EmptyState title="No weather points available" detail="The dashboard does not substitute fabricated scores when the live pipeline is unavailable." />}
        </div>
        <div className="surface dashboard-map">
          <SectionHeader title="National outlook" action="Open full map" onAction={() => navigate("/map")} />
          <RiskMapVisual national={national} weatherSnapshot={weatherSnapshot} compact />
        </div>
      </section>
      <section className="dashboard-bottom">
        <div className="surface forecast-panel">
          <SectionHeader title="Nationwide monitor" meta={weatherSnapshot?.model_version ?? "Awaiting model version"} action="Explore map" onAction={() => navigate("/map")} />
          <InterestGridPanel snapshot={weatherSnapshot} navigate={navigate} />
        </div>
        <div className="surface alert-summary">
          <SectionHeader title="Alert summary" meta={`${national?.active_alerts ?? 0} active`} action="View all alerts" onAction={() => navigate("/alerts")} />
          {alerts.map((alert) => <button key={alert.alert_id} onClick={() => navigate("/alerts")}><AlertTriangle size={16} /><span>{alert.event}</span><strong>{alert.score}</strong><ChevronRight size={15} /></button>)}
          {!alerts.length && <EmptyState title="No active alerts loaded" detail="NWS alert records will appear here." />}
        </div>
      </section>
    </main>
  );
}

export function SavedPage({
  navigate,
  weatherSnapshot,
  dataStatus,
}: { navigate: Navigate; weatherSnapshot: NationalWeatherSnapshot | null; dataStatus: DataStatus }) {
  const { t } = useI18n();
  const [query, setQuery] = useState("");
  const [highestRisk, setHighestRisk] = useState(false);
  const [placesState, setPlacesState] = useState<SavedPlaceRecord[]>([]);
  const [loading, setLoading] = useState(Boolean(currentUser()));
  const userEmail = currentUser()?.email ?? null;

  useEffect(() => {
    if (!userEmail) return;
    void api.savedPlaces().then(setPlacesState).catch(() => notify("Saved places could not be loaded.")).finally(() => setLoading(false));
  }, [userEmail]);

  const filtered = useMemo(() => {
    const matching = placesState.filter((place) => place.name.toLowerCase().includes(query.toLowerCase()));
    return highestRisk ? [...matching].sort((a, b) => (b.currentRiskScore ?? 0) - (a.currentRiskScore ?? 0)) : matching;
  }, [highestRisk, placesState, query]);
  const selected = filtered[0] ?? placesState[0] ?? null;

  return (
    <main className="saved-layout">
      <aside className="collections-panel">
        <h3>Collections</h3>
        <button className="active"><Folder size={17} /><span>Saved places</span><em>{placesState.length}</em></button>
        <div className="collection-insight">
          <strong>Account insights</strong>
          <span>High-risk places <b>{placesState.filter((place) => (place.currentRiskScore ?? 0) >= 55).length}</b></span>
          <span>Weather coverage <b>{Math.round((weatherSnapshot?.coverage ?? 0) * 100)}%</b></span>
        </div>
      </aside>
      <section className="saved-main">
        <PageTitle title={t("saved.title")} subtitle={t("saved.subtitle")}>
          <button className="button primary" onClick={() => navigate("/map")}><Plus size={16} /> {t("saved.addFromMap")}</button>
        </PageTitle>
        <DataNotice status={dataStatus} hasData={Boolean(weatherSnapshot)} />
        {!userEmail ? (
          <CallToAction title={t("saved.signInTitle")} detail={t("saved.signInDetail")} action={t("saved.signInAction")} onClick={() => void login()} secondaryAction={t("saved.googleAction")} onSecondaryClick={() => {
            if (googleAuthConfigured()) {
              void loginWithGoogle();
            } else {
              notify(t("header.googleUnavailable"));
            }
          }} />
        ) : loading ? (
          <EmptyState title="Loading saved places" detail="Reading your records from the platform API." />
        ) : (
          <>
            <div className="saved-toolbar">
              <label><Search size={17} /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search saved places..." /></label>
              <button className={highestRisk ? "active" : ""} onClick={() => setHighestRisk((value) => !value)}><Filter size={16} /> Highest risk</button>
            </div>
            <div className="saved-grid">
              {filtered.map((place) => {
                const score = place.currentRiskScore ?? 0;
                return <button key={place.savedItemId} onClick={() => navigate(`/map?search=${encodeURIComponent(place.name)}`)}><MapThumb seed={place.savedItemId} /><span className="saved-card-title"><MapPin size={15} /><strong>{place.name}</strong></span><span className="saved-risk"><b className={riskLevel(score)}>{score}</b><i>{riskLevelLabel(score)} risk</i><small>Private saved place</small></span><span className="saved-meta"><Bell size={13} /> DynamoDB record</span></button>;
              })}
              {!filtered.length && <CallToAction title="No saved places yet" detail="Search anywhere in the United States, open a place, and save it to build your watchlist." action="Explore the map" onClick={() => navigate("/map")} />}
            </div>
            <InterestGridPanel snapshot={weatherSnapshot} navigate={navigate} compact />
          </>
        )}
      </section>
      <aside className="saved-inspector">
        {selected ? <>
          <div className="inspector-title"><MapPin size={18} /><div><h2>{selected.name}</h2><span>Saved place</span></div><Bookmark size={18} /></div>
          <MapThumb seed={`${selected.savedItemId}-large`} large />
          <div className="selected-risk"><b className={riskLevel(selected.currentRiskScore ?? 0)}>{selected.currentRiskScore ?? "--"}</b><span><strong>{riskLevelLabel(selected.currentRiskScore ?? 0)} risk</strong><small>Last stored composite score</small></span></div>
          <button className="button primary wide" onClick={() => navigate(`/map?search=${encodeURIComponent(selected.name)}`)}>Open on map</button>
          <button className="button secondary wide" onClick={() => navigate("/directions")}>Plan route from here</button>
        </> : <EmptyState title="Nothing selected" detail="Saved-place details will appear here." />}
      </aside>
    </main>
  );
}

export function AlertsPage({ navigate, national, dataStatus }: LiveProps) {
  const { t } = useI18n();
  const [severeOnly, setSevereOnly] = useState(false);
  const alerts = useMemo(() => {
    const live = national?.alerts ?? [];
    return severeOnly ? live.filter((alert) => ["Extreme", "Severe"].includes(alert.severity)) : live;
  }, [national, severeOnly]);
  const [selectedId, setSelectedId] = useState("");
  const selected = alerts.find((alert) => alert.alert_id === selectedId) ?? alerts[0] ?? null;

  return (
    <main className="alerts-page">
      <section className="alerts-main">
        <PageTitle title={t("alerts.title")} subtitle={t("alerts.subtitle")}><button className="button secondary" onClick={() => setSevereOnly((value) => !value)}><SlidersHorizontal size={16} /> {severeOnly ? t("alerts.showAll") : t("alerts.severeOnly")}</button></PageTitle>
        <DataNotice status={dataStatus} hasData={Boolean(national)} />
        <div className="alerts-workspace">
          <div className="alerts-list">
            <strong>{alerts.length} active alerts</strong>
            {alerts.map((alert) => <button className={selected?.alert_id === alert.alert_id ? "selected" : ""} key={alert.alert_id} onClick={() => setSelectedId(alert.alert_id)}><AlertTriangle size={18} /><span><strong>{alert.event}</strong><small>{alert.area || "Affected U.S. region"}</small><em>NWS live alert</em></span><i className={riskClass(alert.severity)}>{alert.severity}</i><ChevronRight size={15} /></button>)}
            {!alerts.length && <EmptyState title="No live alert records available" detail="This page intentionally stays empty instead of displaying fabricated warnings." />}
          </div>
          <div className="alerts-map"><RiskMapVisual national={national} regional /></div>
        </div>
      </section>
      <aside className="alert-inspector">
        {selected ? <>
          <div className="inspector-title"><AlertTriangle size={20} /><div><h2>{selected.event}</h2><span className={riskClass(selected.severity)}>{selected.severity}</span></div><Bookmark size={18} /></div>
          <InspectorMetric label="Certainty" value={selected.certainty || "Unknown"} />
          <InspectorMetric label="Urgency" value={selected.urgency || "Unknown"} />
          <h4>What's happening</h4><p>{selected.headline}</p>
          <h4>Affected area</h4><p>{selected.area || "Area details unavailable"}</p>
          {selected.instruction && <><h4>Official guidance</h4><p className="recommendation"><ShieldCheck size={18} /> {selected.instruction}</p></>}
          <button className="button primary wide" onClick={() => navigate("/directions")}>Compare route alternatives <ArrowRight size={16} /></button>
          <button className="button secondary wide" onClick={() => navigate("/map")}>Open national map</button>
        </> : <EmptyState title="Select an alert" detail="Official alert details will appear here." />}
      </aside>
    </main>
  );
}

export function PlaceDetailPage({ navigate, slug }: { navigate: Navigate; slug: string }) {
  const [risk, setRisk] = useState<LocationRisk | null>(null);
  const [loading, setLoading] = useState(true);
  const place = places[slug] ?? places.miami;
  useEffect(() => {
    setLoading(true);
    void api.locationRisk(place).then(setRisk).catch(() => setRisk(null)).finally(() => setLoading(false));
  }, [place]);

  const reasons = risk ? [
    { name: "Precipitation", contribution: risk.factors.precipitation ?? 0, icon: CloudRain },
    { name: "Flood exposure", contribution: risk.factors.flood ?? 0, icon: Droplets },
    { name: "Wind", contribution: risk.factors.wind ?? 0, icon: Wind },
    { name: "Active alerts", contribution: risk.factors.active_alerts ?? 0, icon: AlertTriangle },
  ] : [];

  return (
    <main className="page-shell place-page">
      <button className="back-link" onClick={() => navigate("/map")}>Back to map</button>
      <section className="place-heading">
        <div><h1>{place.city}, {place.state}</h1><span>{risk ? `Updated ${formatTime(risk.generated_at)}` : loading ? "Loading live risk..." : "Live risk unavailable"}</span></div>
        <Metric label="Composite risk" value={risk ? `${risk.score}` : "--"} meta={risk?.level ?? "No live value"} tone={risk ? riskLevel(risk.score) : "low"} />
        <Metric label="Temperature" value={risk ? `${Math.round(risk.weather.temperature_f)}°F` : "--"} meta={risk?.weather.source ?? "Awaiting provider"} tone="low" />
        <Metric label="Wind" value={risk ? `${Math.round(risk.weather.wind_speed_mph)} mph` : "--"} meta={risk ? `${Math.round(risk.weather.precipitation_probability)}% precipitation` : "No live value"} tone="moderate" />
        <div className="place-actions"><button className="button primary" onClick={() => navigate("/directions")}>Plan a route</button><button className="button secondary" onClick={() => savePlace(place, risk?.score)}><Bookmark size={15} /> Save place</button></div>
      </section>
      {!risk && <DataNotice status={loading ? "loading" : "degraded"} hasData={false} />}
      <section className="place-top">
        <div className="surface place-map"><RiskMapVisual locationRisk={risk} regional />{risk && <Timeline score={risk.score} />}</div>
        <div className="surface why-risk">
          <SectionHeader title="Risk factors" meta={risk?.model_version} />
          {reasons.map(({ name, contribution, icon: Icon }) => <div className="risk-reason" key={name}><Icon size={22} /><span><strong>{name}</strong><small>Live composite contribution</small></span><em className={riskLevel(contribution)}>{riskLevelLabel(contribution)}</em><b>{contribution}<small>/100</small></b></div>)}
          {!reasons.length && <EmptyState title="Risk factors unavailable" detail="No fallback factor values are shown." />}
        </div>
      </section>
      <section className="place-bottom">
        <div className="surface active-place-alerts"><SectionHeader title="Active alerts" action="View all alerts" onAction={() => navigate("/alerts")} />{risk?.alerts.map((alert) => <button key={alert.alert_id} onClick={() => navigate("/alerts")}><AlertTriangle size={16} /><span>{alert.event}</span><small>{alert.area}</small><ChevronRight size={15} /></button>)}{risk && !risk.alerts.length && <EmptyState title="No active alerts" detail="No NWS alerts intersect this place." />}</div>
        <div className="surface live-context"><strong>Live conditions</strong>{risk ? <><span><CloudRain /> {Math.round(risk.weather.temperature_f)}°F</span><span><Droplets /> {Math.round(risk.weather.precipitation_probability)}% rain</span><span><Wind /> {Math.round(risk.weather.wind_speed_mph)} mph</span></> : <span>Unavailable</span>}</div>
      </section>
    </main>
  );
}

function DataNotice({ status, hasData }: { status: DataStatus; hasData: boolean }) {
  const { t } = useI18n();
  if (status === "ready") return null;
  return <div className={`data-notice ${status}`}><Gauge size={17} /><span><strong>{status === "loading" ? t("data.loading") : hasData ? t("data.partial") : t("data.unavailable")}</strong><small>{status === "loading" ? t("data.loadingDetail") : t("data.unavailableDetail")}</small></span></div>;
}

function EmptyState({ title, detail }: { title: string; detail: string }) {
  return <div className="inline-empty"><CloudRain size={22} /><strong>{title}</strong><small>{detail}</small></div>;
}

function CallToAction({ title, detail, action, onClick, secondaryAction, onSecondaryClick }: { title: string; detail: string; action: string; onClick: () => void; secondaryAction?: string; onSecondaryClick?: () => void }) {
  return <div className="surface saved-empty"><Bookmark size={28} /><h2>{title}</h2><p>{detail}</p><div className="cta-actions"><button className="button primary" onClick={onClick}>{action}</button>{secondaryAction && onSecondaryClick && <button className="button secondary" onClick={onSecondaryClick}>{secondaryAction}</button>}</div></div>;
}

function PageTitle({ title, subtitle, children }: { title: string; subtitle: string; children?: React.ReactNode }) {
  return <header className="page-title"><div><h1>{title}</h1><p>{subtitle}</p></div><div>{children}</div></header>;
}

function SectionHeader({ title, meta, action, onAction }: { title: string; meta?: string; action?: string; onAction?: () => void }) {
  return <header className="section-header"><div><h2>{title}</h2>{meta && <span>{meta}</span>}</div>{action && onAction && <button onClick={onAction}>{action} <ArrowRight size={14} /></button>}</header>;
}

function QuickAction({ icon, title, subtitle, onClick }: { icon: React.ReactNode; title: string; subtitle: string; onClick: () => void }) {
  return <button className="quick-action" onClick={onClick}><i>{icon}</i><span><strong>{title}</strong><small>{subtitle}</small></span></button>;
}

function RiskMapVisual({
  national,
  weatherSnapshot,
  locationRisk,
  compact,
  regional,
}: {
  national?: NationalRiskOverview | null;
  weatherSnapshot?: NationalWeatherSnapshot | null;
  locationRisk?: LocationRisk | null;
  compact?: boolean;
  regional?: boolean;
}) {
  const weatherPoints = locationRisk ? [locationRisk.weather] : topWeatherPoints(weatherSnapshot ?? null, compact ? 5 : 12);
  const alertPoints = locationRisk ? locationRisk.alerts : national?.alerts ?? [];
  const hasLiveData = weatherPoints.length > 0 || alertPoints.some((alert) => alert.longitude != null && alert.latitude != null);
  return (
    <div className={`risk-map-visual ${compact ? "compact" : ""} ${regional ? "regional" : ""}`}>
      {weatherPoints.map((point) => {
        const position = projectPoint(point.longitude, point.latitude);
        const level = riskLevel(point.risk_score);
        return (
          <div key={point.id} className={`live-risk-point ${level}`} style={{ left: `${position.x}%`, top: `${position.y}%` }}>
            <b className={level}>{point.risk_score}</b>
            <span>{point.city}</span>
            <small>{Math.round(point.precipitation_probability)}% rain · {Math.round(point.wind_speed_mph)} mph</small>
          </div>
        );
      })}
      {weatherPoints.map((point) => {
        const position = projectPoint(point.longitude, point.latitude);
        const size = compact ? 60 : 95;
        return <i key={`${point.id}-field`} className={`live-risk-blob ${riskLevel(point.risk_score)}`} style={{ left: `${position.x}%`, top: `${position.y}%`, width: size, height: size }} />;
      })}
      {alertPoints.filter((alert) => alert.longitude != null && alert.latitude != null).map((alert) => {
        const position = projectPoint(alert.longitude as number, alert.latitude as number);
        return <span key={alert.alert_id} className="live-alert-point" style={{ left: `${position.x}%`, top: `${position.y}%` }} title={alert.event} />;
      })}
      {!hasLiveData && <div className="risk-map-empty"><EmptyState title="Live outlook unavailable" detail="No hardcoded cities or fabricated weather are shown when live feeds are unavailable." /></div>}
      <div className="risk-legend"><strong>Risk level</strong><i /><span>Low</span><span>Extreme</span></div>
      {national && <small className="map-status">{national.active_alerts} active alerts · {national.severe_alerts} severe</small>}
      {locationRisk && <small className="map-status">{locationRisk.place.city}, {locationRisk.place.state} · risk {locationRisk.score}</small>}
    </div>
  );
}

function InterestGridPanel({ snapshot, navigate, compact }: { snapshot: NationalWeatherSnapshot | null; navigate: Navigate; compact?: boolean }) {
  const points = useMemo(() => topWeatherPoints(snapshot, compact ? 6 : 12), [compact, snapshot]);
  return <section className={`interest-grid ${compact ? "compact" : ""}`}><div>{points.map((point) => <button key={point.id} onClick={() => navigate(`/map?search=${encodeURIComponent(point.city)}`)}><span><strong>{point.city}</strong><small>{weatherSummary(point)}</small></span><i className={riskLevel(point.risk_score)}>{point.risk_score}</i></button>)}</div>{!points.length && <EmptyState title="No monitored points available" detail="Waiting for the nationwide weather snapshot." />}</section>;
}

function AlertStory({ alert, index, onClick }: { alert: RiskAlert; index: number; onClick: () => void }) {
  return <button className="alert-story" onClick={onClick}><i className={riskClass(alert.severity)}>{index + 1}</i><span><strong>{alert.event}</strong><small>{alert.area || "Affected U.S. region"}</small><p>{alert.headline}</p></span><b className={riskClass(alert.severity)}>{alert.severity}</b><ChevronRight size={16} /></button>;
}

function MapThumb({ seed, large }: { seed: string; large?: boolean }) {
  return <div className={`map-thumb ${large ? "large" : ""}`} data-seed={seed}><span className="thumb-weather high" /><MapPin size={large ? 22 : 16} /></div>;
}

function InspectorMetric({ label, value }: { label: string; value: string }) {
  return <div className="inspector-metric"><span>{label}</span><strong>{value}</strong></div>;
}

function Metric({ label, value, meta, tone }: { label: string; value: string; meta: string; tone: string }) {
  return <div className="place-metric"><span>{label}</span><strong className={tone}>{value}</strong><small>{meta}</small></div>;
}

function Timeline({ score }: { score: number }) {
  return <div className="risk-timeline"><strong>Current route-risk context</strong><div>{Array.from({ length: 44 }, (_, index) => <i key={index} className={riskLevel(score)} />)}</div><span><small>Current snapshot</small><small>Live composite score {score}</small></span></div>;
}

function topWeatherPoints(snapshot: NationalWeatherSnapshot | null, limit: number) {
  return [...(snapshot?.points ?? [])].filter((point) => point.data_status !== "UNAVAILABLE").sort((a, b) => b.risk_score - a.risk_score).slice(0, limit);
}

function topAlerts(national: NationalRiskOverview | null, limit: number) {
  return [...(national?.alerts ?? [])].sort((a, b) => b.score - a.score).slice(0, limit);
}

function weatherSummary(point: WeatherRisk) {
  return `${Math.round(point.temperature_f)}°F · ${Math.round(point.wind_speed_mph)} mph wind · ${Math.round(point.precipitation_probability)}% rain`;
}

function riskLevelLabel(score: number) {
  if (score >= 80) return "Severe";
  if (score >= 55) return "High";
  if (score >= 30) return "Moderate";
  return "Low";
}

function riskClass(level?: string) {
  return (level ?? "low").toLowerCase();
}

function formatTime(value?: string) {
  if (!value) return "not available";
  return new Intl.DateTimeFormat("en-US", { hour: "numeric", minute: "2-digit", timeZoneName: "short" }).format(new Date(value));
}

function projectPoint(longitude: number, latitude: number) {
  const west = -125;
  const east = -66;
  const north = 49;
  const south = 24;
  return {
    x: clamp(((longitude - west) / (east - west)) * 100, 6, 94),
    y: clamp(((north - latitude) / (north - south)) * 100, 8, 92),
  };
}

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}

function savePlace(place: (typeof places)[string], score?: number) {
  if (!currentUser()) {
    notify("Sign in to save this place to your private watchlist.");
    void login();
    return;
  }
  void api.savePlace(place, score).then(() => notify(`${place.city} saved to your account.`)).catch(() => notify("This place could not be saved."));
}
