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
  Newspaper,
  Navigation,
  Plus,
  Search,
  ShieldCheck,
  SlidersHorizontal,
  Snowflake,
  Wind,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";

import type { DataStatus, Navigate } from "./App";
import { api } from "./api";
import { currentUser, googleAuthConfigured, login, loginWithGoogle } from "./auth";
import { LiveRiskMap } from "./components/LiveRiskMap";
import { useI18n } from "./i18n";
import { places, riskLevel } from "./mockData";
import type {
  LocationRisk,
  NationalRiskOverview,
  NationalWeatherSnapshot,
  RiskAlert,
  SavedPlaceRecord,
  SavedRouteRisk,
  SavedRouteRecord,
  WeatherRasterManifest,
  WeatherRisk,
} from "./types";
import { notify } from "./ui";

type LiveProps = {
  navigate: Navigate;
  national: NationalRiskOverview | null;
  weatherRaster?: WeatherRasterManifest | null;
  dataStatus: DataStatus;
};

export function HomePage({
  navigate,
  national,
  weatherSnapshot,
  weatherRaster,
  dataStatus,
}: LiveProps & { weatherSnapshot: NationalWeatherSnapshot | null }) {
  const { t } = useI18n();
  const [query, setQuery] = useState("");
  const submit = () => navigate(query.trim() ? `/map?search=${encodeURIComponent(query.trim())}` : "/directions");
  const highRisk = topWeatherPoints(weatherSnapshot, 4);
  const alerts = topAlerts(national, 3);
  const winterRisks = winterRoadRisks(weatherSnapshot, 4);
  const intelligence = intelligenceItems(national, weatherSnapshot);

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
          <RiskMapVisual national={national} weatherSnapshot={weatherSnapshot} weatherRaster={weatherRaster} />
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
      <section className="home-bottom">
        <WinterRoadRiskPanel points={winterRisks} navigate={navigate} />
        <IntelligenceFeed items={intelligence} navigate={navigate} />
      </section>
    </main>
  );
}

export function DashboardPage({
  navigate,
  national,
  weatherSnapshot,
  weatherRaster,
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
          <RiskMapVisual national={national} weatherSnapshot={weatherSnapshot} weatherRaster={weatherRaster} compact />
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
  const [routesState, setRoutesState] = useState<SavedRouteRecord[]>([]);
  const [loading, setLoading] = useState(Boolean(currentUser()));
  const [collection, setCollection] = useState<"all" | "routes" | "places">("all");
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [routeRisk, setRouteRisk] = useState<SavedRouteRisk | null>(null);
  const [routeHistory, setRouteHistory] = useState<Array<{ checkedAt: string | null; riskScore: number; riskTrend: string }>>([]);
  const [routeDraft, setRouteDraft] = useState({ name: "", usualDepartureTime: "08:00", riskThreshold: 55, monitorEnabled: true });
  const [savingRoute, setSavingRoute] = useState(false);
  const userEmail = currentUser()?.email ?? null;

  useEffect(() => {
    if (!userEmail) return;
    void Promise.all([api.savedPlaces(), api.savedRoutes()])
      .then(([savedPlaces, savedRoutes]) => {
        setPlacesState(savedPlaces);
        setRoutesState(savedRoutes);
      })
      .catch(() => notify("Saved records could not be loaded."))
      .finally(() => setLoading(false));
  }, [userEmail]);

  const filteredPlaces = useMemo(() => {
    if (collection === "routes") return [];
    const matching = placesState.filter((place) => place.name.toLowerCase().includes(query.toLowerCase()));
    return highestRisk ? [...matching].sort((a, b) => (b.currentRiskScore ?? 0) - (a.currentRiskScore ?? 0)) : matching;
  }, [collection, highestRisk, placesState, query]);
  const filteredRoutes = useMemo(() => {
    if (collection === "places") return [];
    const matching = routesState.filter((route) => route.name.toLowerCase().includes(query.toLowerCase())
      || route.originName.toLowerCase().includes(query.toLowerCase())
      || route.destinationName.toLowerCase().includes(query.toLowerCase()));
    return highestRisk ? [...matching].sort((a, b) => b.riskScore - a.riskScore) : matching;
  }, [collection, highestRisk, query, routesState]);
  const selectedRoute = selectedKey?.startsWith("route:")
    ? filteredRoutes.find((route) => `route:${route.savedItemId}` === selectedKey)
    : null;
  const selectedPlace = selectedKey?.startsWith("place:")
    ? filteredPlaces.find((place) => `place:${place.savedItemId}` === selectedKey)
    : null;
  const selected = selectedRoute
    ? ({ type: "route" as const, value: selectedRoute })
    : selectedPlace
      ? ({ type: "place" as const, value: selectedPlace })
      : filteredRoutes[0]
        ? ({ type: "route" as const, value: filteredRoutes[0] })
        : filteredPlaces[0]
          ? ({ type: "place" as const, value: filteredPlaces[0] })
          : null;

  const selectedRouteId = selected?.type === "route" ? selected.value.savedItemId : null;
  useEffect(() => {
    if (!selectedRouteId) {
      setRouteRisk(null);
      setRouteHistory([]);
      return;
    }
    const route = routesState.find((item) => item.savedItemId === selectedRouteId);
    if (!route) return;
    setRouteDraft({
      name: route.name,
      usualDepartureTime: route.usualDepartureTime,
      riskThreshold: route.riskThreshold,
      monitorEnabled: route.monitorEnabled,
    });
    let cancelled = false;
    void Promise.all([
      api.savedRouteCurrentRisk(route.savedItemId),
      api.savedRouteRiskHistory(route.savedItemId),
    ])
      .then(([currentRisk, history]) => {
        if (cancelled) return;
        setRouteRisk(currentRisk);
        setRouteHistory(history);
      })
      .catch(() => {
        if (cancelled) return;
        setRouteRisk(null);
        setRouteHistory([]);
    });
    return () => { cancelled = true; };
  }, [routesState, selectedRouteId]);

  async function saveRouteSettings() {
    if (selected?.type !== "route") return;
    setSavingRoute(true);
    try {
      const updated = await api.updateSavedRoute(selected.value.savedItemId, {
        name: routeDraft.name.trim() || selected.value.name,
        usualDepartureTime: routeDraft.usualDepartureTime,
        riskThreshold: Math.max(1, Math.min(100, Number(routeDraft.riskThreshold) || 55)),
        monitorEnabled: routeDraft.monitorEnabled,
      });
      setRoutesState((items) => items.map((item) => item.savedItemId === updated.savedItemId ? updated : item));
      notify("Saved route settings updated.");
    } catch {
      notify("Saved route settings could not be updated.");
    } finally {
      setSavingRoute(false);
    }
  }

  async function deleteSelected() {
    if (!selected) return;
    try {
      if (selected.type === "place") {
        await api.deleteSavedPlace(selected.value.savedItemId);
        setPlacesState((items) => items.filter((item) => item.savedItemId !== selected.value.savedItemId));
      } else {
        await api.deleteSavedRoute(selected.value.savedItemId);
        setRoutesState((items) => items.filter((item) => item.savedItemId !== selected.value.savedItemId));
      }
      notify("Saved item removed.");
    } catch {
      notify("Saved item could not be removed.");
    }
  }

  return (
    <main className="saved-layout">
      <aside className="collections-panel">
        <h3>Collections</h3>
        <button className={collection === "all" ? "active" : ""} onClick={() => setCollection("all")}><Folder size={17} /><span>All saved</span><em>{placesState.length + routesState.length}</em></button>
        <button className={collection === "routes" ? "active" : ""} onClick={() => setCollection("routes")}><Navigation size={17} /><span>Routes</span><em>{routesState.length}</em></button>
        <button className={collection === "places" ? "active" : ""} onClick={() => setCollection("places")}><MapPin size={17} /><span>Places</span><em>{placesState.length}</em></button>
        <div className="collection-insight">
          <strong>Account insights</strong>
          <span>High-risk places <b>{placesState.filter((place) => (place.currentRiskScore ?? 0) >= 55).length}</b></span>
          <span>High-risk routes <b>{routesState.filter((route) => route.riskScore >= 55).length}</b></span>
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
              <label><Search size={17} /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search saved routes and places..." /></label>
              <button className={highestRisk ? "active" : ""} onClick={() => setHighestRisk((value) => !value)}><Filter size={16} /> Highest risk</button>
            </div>
            <div className="saved-grid">
              {filteredRoutes.map((route) => (
                <button key={route.savedItemId} className={selected?.type === "route" && selected.value.savedItemId === route.savedItemId ? "selected" : ""} onClick={() => setSelectedKey(`route:${route.savedItemId}`)}>
                  <MapThumb seed={route.savedItemId} />
                  <span className="saved-card-title"><Navigation size={15} /><strong>{route.name}</strong></span>
                  <span className="saved-risk"><b className={riskLevel(route.riskScore)}>{route.riskScore}</b><i>{riskLevelLabel(route.riskScore)} risk</i><small>{formatDistance(route.distanceMiles)} / {formatDuration(route.durationMinutes)}</small></span>
                  <span className="saved-meta"><Bell size={13} /> {route.monitorEnabled ? "Monitoring" : "Paused"} · threshold {route.riskThreshold} · {route.usualDepartureTime}</span>
                  <span className="saved-meta"><ShieldCheck size={13} /> {route.activeHazards.length ? route.activeHazards.slice(0, 2).join(" / ") : "No active route hazards"} · {route.riskTrend.toLowerCase()}</span>
                </button>
              ))}
              {filteredPlaces.map((place) => {
                const score = place.currentRiskScore ?? 0;
                return <button key={place.savedItemId} className={selected?.type === "place" && selected.value.savedItemId === place.savedItemId ? "selected" : ""} onClick={() => setSelectedKey(`place:${place.savedItemId}`)}><MapThumb seed={place.savedItemId} /><span className="saved-card-title"><MapPin size={15} /><strong>{place.name}</strong></span><span className="saved-risk"><b className={riskLevel(score)}>{score}</b><i>{riskLevelLabel(score)} risk</i><small>Private saved place</small></span><span className="saved-meta"><Bell size={13} /> Saved place</span></button>;
              })}
              {!filteredRoutes.length && !filteredPlaces.length && <CallToAction title="No saved items yet" detail="Search anywhere in the United States, save a place, or save a route to build your climate watchlist." action="Explore the map" onClick={() => navigate("/map")} />}
            </div>
            <InterestGridPanel snapshot={weatherSnapshot} navigate={navigate} compact />
          </>
        )}
      </section>
      <aside className="saved-inspector">
        {selected ? <>
          <div className="inspector-title">{selected.type === "route" ? <Navigation size={18} /> : <MapPin size={18} />}<div><h2>{selected.value.name}</h2><span>{selected.type === "route" ? "Saved route" : "Saved place"}</span></div><Bookmark size={18} /></div>
          <MapThumb seed={`${selected.value.savedItemId}-large`} large />
          {selected.type === "route" ? <>
            <div className="selected-risk"><b className={riskLevel(selected.value.riskScore)}>{selected.value.riskScore}</b><span><strong>{riskLevelLabel(selected.value.riskScore)} risk</strong><small>{formatDistance(selected.value.distanceMiles)} · {formatDuration(selected.value.durationMinutes)} · {selected.value.vehicleType}</small></span></div>
            {routeRisk && <div className="route-live-risk"><strong>Current risk</strong><span><b className={riskLevel(routeRisk.currentRiskScore)}>{routeRisk.currentRiskScore}</b>{routeRisk.thresholdExceeded ? "Threshold exceeded" : "Below threshold"}</span><small>Last checked {routeRisk.lastCheckedAt ? formatTime(routeRisk.lastCheckedAt) : "not checked yet"}</small></div>}
            <InspectorMetric label="Origin" value={selected.value.originName} />
            <InspectorMetric label="Destination" value={selected.value.destinationName} />
            <InspectorMetric label="Monitor" value={selected.value.monitorEnabled ? `On at ${selected.value.usualDepartureTime}` : "Paused"} />
            <InspectorMetric label="Risk threshold" value={`${selected.value.riskThreshold}`} />
            <InspectorMetric label="Trend" value={selected.value.riskTrend} />
            <InspectorMetric label="Active hazards" value={selected.value.activeHazards.length ? selected.value.activeHazards.join(", ") : "None"} />
            <div className="saved-editor">
              <label>Route name<input value={routeDraft.name} onChange={(event) => setRouteDraft((draft) => ({ ...draft, name: event.target.value }))} /></label>
              <label>Usual departure<input type="time" value={routeDraft.usualDepartureTime} onChange={(event) => setRouteDraft((draft) => ({ ...draft, usualDepartureTime: event.target.value }))} /></label>
              <label>Risk threshold<input type="number" min={1} max={100} value={routeDraft.riskThreshold} onChange={(event) => setRouteDraft((draft) => ({ ...draft, riskThreshold: Number(event.target.value) }))} /></label>
              <label className="toggle-row"><input type="checkbox" checked={routeDraft.monitorEnabled} onChange={(event) => setRouteDraft((draft) => ({ ...draft, monitorEnabled: event.target.checked }))} /> Monitor this route</label>
              <button className="button secondary wide" disabled={savingRoute} onClick={() => void saveRouteSettings()}>{savingRoute ? "Saving..." : "Save route settings"}</button>
            </div>
            <div className="route-history">
              <strong>Risk trend</strong>
              {routeHistory.length ? routeHistory.map((point) => <span key={`${point.checkedAt}-${point.riskScore}`}><i className={riskLevel(point.riskScore)} /> <b>{point.riskScore}</b> {point.riskTrend} <small>{point.checkedAt ? formatTime(point.checkedAt) : "now"}</small></span>) : <small>No route history yet.</small>}
            </div>
            <button className="button primary wide" onClick={() => navigate(`/directions?origin=${encodeURIComponent(selected.value.originName)}&destination=${encodeURIComponent(selected.value.destinationName)}&vehicle=${selected.value.vehicleType.toLowerCase()}`)}>Re-open route</button>
          </> : <>
            <div className="selected-risk"><b className={riskLevel(selected.value.currentRiskScore ?? 0)}>{selected.value.currentRiskScore ?? "--"}</b><span><strong>{riskLevelLabel(selected.value.currentRiskScore ?? 0)} risk</strong><small>Last stored composite score</small></span></div>
            <button className="button primary wide" onClick={() => navigate(`/map?search=${encodeURIComponent(selected.value.name)}`)}>Open on map</button>
            <button className="button secondary wide" onClick={() => navigate("/directions")}>Plan route from here</button>
          </>}
          <button className="button secondary wide" onClick={() => void deleteSelected()}>Delete saved item</button>
        </> : <EmptyState title="Nothing selected" detail="Saved item details will appear here." />}
      </aside>
    </main>
  );
}

export function AlertsPage({ navigate, national, weatherRaster, dataStatus }: LiveProps) {
  const { t } = useI18n();
  const [severeOnly, setSevereOnly] = useState(false);
  const [savedRoutes, setSavedRoutes] = useState<SavedRouteRecord[]>([]);
  const userEmail = currentUser()?.email ?? null;
  useEffect(() => {
    if (!userEmail) return;
    void api.savedRoutes().then(setSavedRoutes).catch(() => setSavedRoutes([]));
  }, [userEmail]);
  const alerts = useMemo(() => {
    const live = national?.alerts ?? [];
    return severeOnly ? live.filter((alert) => ["Extreme", "Severe"].includes(alert.severity)) : live;
  }, [national, severeOnly]);
  const [selectedId, setSelectedId] = useState("");
  const selected = alerts.find((alert) => alert.alert_id === selectedId) ?? alerts[0] ?? null;
  const impactedRoutes = useMemo(() => selected ? routeImpactsForAlert(selected, savedRoutes) : [], [savedRoutes, selected]);

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
          <div className="alerts-map"><RiskMapVisual national={national} weatherRaster={weatherRaster} regional /></div>
        </div>
      </section>
      <aside className="alert-inspector">
        {selected ? <>
          <div className="inspector-title"><AlertTriangle size={20} /><div><h2>{selected.event}</h2><span className={riskClass(selected.severity)}>{selected.severity}</span></div><Bookmark size={18} /></div>
          <InspectorMetric label="Certainty" value={selected.certainty || "Unknown"} />
          <InspectorMetric label="Urgency" value={selected.urgency || "Unknown"} />
          <h4>What's happening</h4><p>{selected.headline}</p>
          <h4>Affected area</h4><p>{selected.area || "Area details unavailable"}</p>
          <h4>Route impact</h4>
          {userEmail ? (
            impactedRoutes.length ? impactedRoutes.map((impact) => (
              <button key={impact.route.savedItemId} className="route-impact-item" onClick={() => navigate(`/directions?origin=${encodeURIComponent(impact.route.originName)}&destination=${encodeURIComponent(impact.route.destinationName)}&vehicle=${impact.route.vehicleType.toLowerCase()}`)}>
                <Navigation size={16} />
                <span><strong>{impact.route.name}</strong><small>{impact.reason}</small></span>
                <em className={riskLevel(impact.score)}>{impact.score}</em>
              </button>
            )) : <EmptyState title="No saved route impact" detail="Your saved routes do not currently match this alert." />
          ) : <CallToAction title="Sign in for route impact" detail="Saved routes can be matched against active alerts and opened directly in directions." action="Sign in" onClick={() => void login()} secondaryAction="Continue with Google" onSecondaryClick={() => {
            if (googleAuthConfigured()) {
              void loginWithGoogle();
            } else {
              notify(t("header.googleUnavailable"));
            }
          }} />}
          {selected.instruction && <><h4>Official guidance</h4><p className="recommendation"><ShieldCheck size={18} /> {selected.instruction}</p></>}
          <button className="button primary wide" onClick={() => navigate("/directions")}>Compare route alternatives <ArrowRight size={16} /></button>
          <button className="button secondary wide" onClick={() => navigate("/map")}>Open national map</button>
        </> : <EmptyState title="Select an alert" detail="Official alert details will appear here." />}
      </aside>
    </main>
  );
}

export function PlaceDetailPage({ navigate, slug, weatherRaster }: { navigate: Navigate; slug: string; weatherRaster?: WeatherRasterManifest | null }) {
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
        <div className="surface place-map"><RiskMapVisual locationRisk={risk} weatherRaster={weatherRaster} regional />{risk && <Timeline score={risk.score} />}</div>
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
  weatherRaster,
  locationRisk,
  compact,
  regional,
}: {
  national?: NationalRiskOverview | null;
  weatherSnapshot?: NationalWeatherSnapshot | null;
  weatherRaster?: WeatherRasterManifest | null;
  locationRisk?: LocationRisk | null;
  compact?: boolean;
  regional?: boolean;
}) {
  return <LiveRiskMap national={national} weatherSnapshot={weatherSnapshot} weatherRaster={weatherRaster} locationRisk={locationRisk} compact={compact} regional={regional} />;
}

function InterestGridPanel({ snapshot, navigate, compact }: { snapshot: NationalWeatherSnapshot | null; navigate: Navigate; compact?: boolean }) {
  const points = useMemo(() => topWeatherPoints(snapshot, compact ? 6 : 12), [compact, snapshot]);
  return <section className={`interest-grid ${compact ? "compact" : ""}`}><div>{points.map((point) => <button key={point.id} onClick={() => navigate(`/map?search=${encodeURIComponent(point.city)}`)}><span><strong>{point.city}</strong><small>{weatherSummary(point)}</small></span><i className={riskLevel(point.risk_score)}>{point.risk_score}</i></button>)}</div>{!points.length && <EmptyState title="No monitored points available" detail="Waiting for the nationwide weather snapshot." />}</section>;
}

function WinterRoadRiskPanel({ points, navigate }: { points: Array<{ point: WeatherRisk; score: number; reason: string }>; navigate: Navigate }) {
  return (
    <div className="surface winter-risk-card">
      <SectionHeader title="Winter road risk" meta="Black ice model" action="Open map" onAction={() => navigate("/map")} />
      {points.length ? (
        <div className="winter-list">
          {points.map(({ point, score, reason }) => (
            <button key={point.id} onClick={() => navigate(`/map?search=${encodeURIComponent(point.city)}`)}>
              <Snowflake size={18} />
              <span><strong>{point.city}</strong><small>{reason}</small></span>
              <b className={riskLevel(score)}>{score}</b>
            </button>
          ))}
        </div>
      ) : <EmptyState title="No winter road signal" detail="No monitored corridor currently combines freezing temperatures with moisture." />}
    </div>
  );
}

type IntelligenceItem = {
  id: string;
  title: string;
  detail: string;
  score: number;
  kind: "official" | "winter" | "coverage";
  tone?: string;
  target: string;
};

function IntelligenceFeed({ items, navigate }: { items: IntelligenceItem[]; navigate: Navigate }) {
  return (
    <div className="surface intelligence-feed">
      <SectionHeader title="Operational intelligence" meta="Official + derived signals" action="View alerts" onAction={() => navigate("/alerts")} />
      <div className="intelligence-list">
        {items.map((item) => (
          <button key={item.id} onClick={() => navigate(item.target)}>
            {item.kind === "winter" ? <Snowflake size={18} /> : item.kind === "official" ? <Newspaper size={18} /> : <Gauge size={18} />}
            <span><strong>{item.title}</strong><small>{item.detail}</small></span>
            <b className={item.tone ?? riskLevel(item.score)}>{item.score}</b>
          </button>
        ))}
      </div>
    </div>
  );
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

function winterRoadRisks(snapshot: NationalWeatherSnapshot | null, limit: number) {
  return [...(snapshot?.points ?? [])]
    .filter((point) => point.data_status !== "UNAVAILABLE")
    .map((point) => ({ point, score: blackIceScore(point), reason: blackIceReason(point) }))
    .filter((item) => item.score >= 25)
    .sort((a, b) => b.score - a.score)
    .slice(0, limit);
}

function blackIceScore(point: WeatherRisk) {
  if (point.temperature_f > 38 || point.precipitation_probability < 15) return 0;
  const freezeFactor = point.temperature_f <= 32 ? 45 : 24;
  const moistureFactor = Math.min(45, point.precipitation_probability * 0.45);
  const windFactor = point.wind_speed_mph >= 15 ? 10 : 0;
  return Math.min(100, Math.round(freezeFactor + moistureFactor + windFactor));
}

function blackIceReason(point: WeatherRisk) {
  const temp = Math.round(point.temperature_f);
  const precip = Math.round(point.precipitation_probability);
  if (point.temperature_f <= 32) return `${temp}°F with ${precip}% precipitation probability`;
  return `Near-freezing ${temp}°F with ${precip}% moisture signal`;
}

function intelligenceItems(national: NationalRiskOverview | null, snapshot: NationalWeatherSnapshot | null): IntelligenceItem[] {
  const official = topAlerts(national, 3).map((alert) => ({
    id: `alert-${alert.alert_id}`,
    title: alert.event,
    detail: alert.headline || alert.area || "Official National Weather Service alert",
    score: alert.score,
    kind: "official" as const,
    target: "/alerts",
  }));
  const winter = winterRoadRisks(snapshot, 2).map(({ point, score, reason }) => ({
    id: `winter-${point.id}`,
    title: `${point.city} black ice watch`,
    detail: reason,
    score,
    kind: "winter" as const,
    target: `/map?search=${encodeURIComponent(point.city)}`,
  }));
  const coverage = {
    id: "coverage",
    title: "Live data coverage",
    detail: `${Math.round((snapshot?.coverage ?? 0) * 100)}% of monitored city and interstate points are live`,
    score: Math.round((snapshot?.coverage ?? 0) * 100),
    kind: "coverage" as const,
    tone: (snapshot?.coverage ?? 0) >= 0.8 ? "low" : "moderate",
    target: "/dashboard",
  };
  return [...official, ...winter, coverage].slice(0, 5);
}

function routeImpactsForAlert(alert: RiskAlert, routes: SavedRouteRecord[]) {
  const text = `${alert.area} ${alert.headline} ${alert.event}`.toLowerCase();
  return routes
    .map((route) => {
      const routeText = `${route.name} ${route.originName} ${route.destinationName}`.toLowerCase();
      const endpointMatch = routeText.split(/[,\s]+/).filter((token) => token.length > 3).some((token) => text.includes(token));
      const weatherSensitive = route.riskScore >= 30 && ["FLOOD", "RAIN", "THUNDERSTORM", "WIND"].some((keyword) => alert.event.toUpperCase().includes(keyword));
      const score = Math.min(100, Math.max(route.riskScore, endpointMatch ? alert.score : Math.round((route.riskScore + alert.score) / 2)));
      const reason = endpointMatch
        ? "Alert overlaps a saved route endpoint or corridor label."
        : weatherSensitive
          ? "Route already has elevated weather risk during this alert."
          : "No strong route overlap detected.";
      return { route, score, reason, included: endpointMatch || weatherSensitive };
    })
    .filter((impact) => impact.included)
    .sort((a, b) => b.score - a.score);
}

function weatherSummary(point: WeatherRisk) {
  return `${Math.round(point.temperature_f)}°F · ${Math.round(point.wind_speed_mph)} mph wind · ${Math.round(point.precipitation_probability)}% rain`;
}

function formatDistance(miles: number) {
  return `${Math.round(miles).toLocaleString()} mi`;
}

function formatDuration(minutes: number) {
  const rounded = Math.round(minutes);
  return `${Math.floor(rounded / 60)} hr ${rounded % 60} min`;
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

function savePlace(place: (typeof places)[string], score?: number) {
  if (!currentUser()) {
    notify("Sign in to save this place to your private watchlist.");
    void login();
    return;
  }
  void api.savePlace(place, score).then(() => notify(`${place.city} saved to your account.`)).catch(() => notify("This place could not be saved."));
}
