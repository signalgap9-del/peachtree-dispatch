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
import { useSearchParams } from "react-router-dom";

import type { DataStatus, Navigate } from "./App";
import { api } from "./api";
import { currentUser, googleAuthConfigured, login, loginWithGoogle } from "./auth";
import { LiveRiskMap } from "./components/LiveRiskMap";
import { useI18n } from "./i18n";
import { places, riskLevel } from "./mockData";
import type {
  AccountSummary,
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

type AlertCategory = "all" | "flood" | "heat" | "storm" | "wind" | "winter" | "fire";

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
  dataStatus,
}: LiveProps & { weatherSnapshot: NationalWeatherSnapshot | null }) {
  const { t } = useI18n();
  const rows = useMemo(() => topWeatherPoints(weatherSnapshot, 8), [weatherSnapshot]);
  const [alertQuery, setAlertQuery] = useState("");
  const [alertCategory, setAlertCategory] = useState<AlertCategory>("all");
  const alerts = useMemo(() => dashboardAlertResults(national, alertQuery, alertCategory, 5), [alertCategory, alertQuery, national]);
  const relatedWeather = useMemo(() => weatherSearchResults(weatherSnapshot, alertQuery, alertCategory).slice(0, 3), [alertCategory, alertQuery, weatherSnapshot]);
  const alertChips = alertCategoryOptions.filter((category) => category !== "all");
  const openAlertCenter = () => {
    const params = new URLSearchParams();
    if (alertQuery.trim()) params.set("q", alertQuery.trim());
    if (alertCategory !== "all") params.set("category", alertCategory);
    navigate(`/alerts${params.toString() ? `?${params.toString()}` : ""}`);
  };
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
          <div className="matrix-head"><span>Area</span><span>Risk</span><span>Condition</span><span>Road impact</span><span>Wind</span></div>
          {rows.map((point) => (
            <button key={point.id} onClick={() => navigate(`/map?search=${encodeURIComponent(point.city)}`)}>
              <span><strong>{point.city}</strong><small>{point.source ?? "NOAA/NWS"}</small></span>
              <i className={riskLevel(point.risk_score)}>{point.risk_score}</i>
              <em>{conditionLabel(point)}</em>
              <em>{roadImpactLabel(point)}</em>
              <em>{windLabel(point)}</em>
            </button>
          ))}
          {!rows.length && <EmptyState title="No weather points available" detail="The dashboard does not substitute fabricated scores when the live pipeline is unavailable." />}
        </div>
        <div className="surface dashboard-alert-intel">
          <SectionHeader title="Search live alerts" meta={`${national?.active_alerts ?? 0} active NWS alerts`} action="Open alert center" onAction={openAlertCenter} />
          <label className="alert-search-preview">
            <Search size={18} />
            <input value={alertQuery} onChange={(event) => setAlertQuery(event.target.value)} onKeyDown={(event) => event.key === "Enter" && openAlertCenter()} placeholder="Search flood, heat, Miami, I-95..." />
          </label>
          <div className="alert-chip-row">
            {alertChips.map((category) => <button key={category} className={alertCategory === category ? "active" : ""} onClick={() => setAlertCategory((current) => current === category ? "all" : category)}>{alertCategoryLabel(category)}</button>)}
          </div>
          <div className="dashboard-alert-list">
            {alerts.map((alert) => <button key={alert.alert_id} onClick={() => navigate(`/alerts?q=${encodeURIComponent(alert.event)}${alertCategory !== "all" ? `&category=${alertCategory}` : ""}`)}><AlertTriangle size={16} /><span><strong>{alert.event}</strong><small>{alert.area || "Affected U.S. region"}</small></span><em className={riskClass(alert.severity)}>{alertDriverAction(alert)}</em></button>)}
            {!alerts.length && <EmptyState title="No matching live alerts" detail="Try another city, county, highway, or hazard category. We do not invent warnings." />}
          </div>
          <div className="dashboard-alert-signals">
            <strong>Map signals for this search</strong>
            {relatedWeather.length ? relatedWeather.map((point) => <button key={point.id} onClick={() => navigate(`/map?search=${encodeURIComponent(point.city)}`)}><span>{point.city}</span><small>{conditionLabel(point)} / {roadImpactLabel(point)}</small><em className={riskLevel(point.risk_score)}>{point.risk_score}</em></button>) : <small>No monitored weather point matches this search yet.</small>}
          </div>
          <div className="roadwork-source-card">
            <Newspaper size={17} />
            <span><strong>Roadwork and closure feed</strong><small>Next external layer: FHWA WZDx + state 511 work-zone feeds. No air-quality filler.</small></span>
            <button onClick={() => navigate("/alerts?q=closure")}>Track</button>
          </div>
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
  const [account, setAccount] = useState<AccountSummary | null>(null);
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
    void Promise.all([api.savedPlaces(), api.savedRoutes(), api.accountSummary().catch(() => null)])
      .then(([savedPlaces, savedRoutes, accountSummary]) => {
        setPlacesState(savedPlaces);
        setRoutesState(savedRoutes);
        setAccount(accountSummary);
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
          <span>Plan <b>{account?.plan.code ?? "Preview"}</b></span>
          <span>Saved routes <b>{routesState.length}/{account?.savedRoutes.limit ?? "?"}</b></span>
          <span>Saved places <b>{placesState.length}/{account?.savedPlaces.limit ?? "?"}</b></span>
          <span>High-risk watchlist <b>{placesState.filter((place) => (place.currentRiskScore ?? 0) >= 55).length + routesState.filter((route) => route.riskScore >= 55).length}</b></span>
          <button className="collection-link" onClick={() => navigate("/usage")}>Usage and limits</button>
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

export function UsagePage({ navigate }: { navigate: Navigate }) {
  const [account, setAccount] = useState<AccountSummary | null>(null);
  const [loading, setLoading] = useState(Boolean(currentUser()));
  const [error, setError] = useState("");
  const userEmail = currentUser()?.email ?? null;

  useEffect(() => {
    if (!userEmail) return;
    setLoading(true);
    void api.accountSummary()
      .then(setAccount)
      .catch(() => setError("Account usage could not be loaded. Check OAuth and platform API deployment settings."))
      .finally(() => setLoading(false));
  }, [userEmail]);

  if (!userEmail) {
    return (
      <main className="page-shell usage-page">
        <CallToAction
          title="Sign in to view usage"
          detail="Usage, quotas, and saved route capacity are tenant-scoped. Public map preview stays available without login."
          action="Sign in"
          onClick={() => void login()}
          secondaryAction="Compare plans"
          onSecondaryClick={() => navigate("/pricing")}
        />
      </main>
    );
  }

  return (
    <main className="page-shell usage-page">
      <PageTitle title="Usage and operations" subtitle="Plan limits, quota enforcement, and production readiness signals for this workspace">
        <button className="button secondary" onClick={() => navigate("/pricing")}>Compare plans</button>
        <button className="button primary" onClick={() => navigate("/directions")}>Plan route <Navigation size={16} /></button>
      </PageTitle>
      {loading && <EmptyState title="Loading account usage" detail="Reading workspace summary from the platform API." />}
      {error && <div className="data-notice degraded" role="status"><AlertTriangle size={17} /><span><strong>Usage unavailable</strong><small>{error}</small></span></div>}
      {account && (
        <>
          <section className="usage-hero surface">
            <div>
              <span className="eyebrow">Workspace</span>
              <h2>{account.workspace.name}</h2>
              <p>{account.user.email || account.user.subject}</p>
            </div>
            <PlanBadge account={account} />
            <div className="usage-hero-actions">
              <button className="button secondary" onClick={() => navigate("/saved")}>Manage watchlist</button>
              <button className="button primary" onClick={() => navigate("/alerts")}>Open alerts</button>
            </div>
          </section>
          <section className="usage-grid">
            <div className="surface usage-panel">
              <SectionHeader title="Daily metered API usage" meta={`Resets ${formatReset(account.dailyUsage[0]?.resetsAt)}`} />
              <div className="usage-meter-list">
                {account.dailyUsage.map((usage) => <UsageMeter key={usage.feature} usage={usage} />)}
              </div>
            </div>
            <div className="surface usage-panel">
              <SectionHeader title="Saved asset capacity" meta="Tenant-scoped private data" />
              <div className="usage-meter-list">
                <UsageMeter usage={{ ...account.savedRoutes, resetsAt: "capacity" }} />
                <UsageMeter usage={{ ...account.savedPlaces, resetsAt: "capacity" }} />
              </div>
            </div>
          </section>
          <section className="surface readiness-panel">
            <SectionHeader title="Production readiness signals" meta="What an operator can verify during incident response" />
            <div className="readiness-grid">
              {account.readiness.map((signal) => (
                <article key={signal.key}>
                  <ShieldCheck size={20} />
                  <span><strong>{signal.label}</strong><small>{signal.detail}</small></span>
                  <em>{signal.state.replaceAll("_", " ")}</em>
                </article>
              ))}
            </div>
          </section>
        </>
      )}
    </main>
  );
}

export function PricingPage({ navigate }: { navigate: Navigate }) {
  const plans = [
    { code: "FREE", title: "Free preview", price: "$0", detail: "Portfolio-safe public preview with server-side quotas.", limits: ["30 route plans / day", "10 saved routes", "25 saved places", "7 days route history"] },
    { code: "PRO", title: "Pro", price: "Billing disabled", detail: "Target SaaS tier for individual power users once payments are added.", limits: ["300 route plans / day", "100 saved routes", "250 saved places", "Dispatch optimizer enabled"] },
    { code: "TEAM", title: "Team", price: "Billing disabled", detail: "Future workspace tier for fleets, consultants, and operations teams.", limits: ["2,000 route plans / day", "1,000 saved routes", "Team workspace controls", "90 days route history"] },
  ];
  return (
    <main className="page-shell pricing-page">
      <PageTitle title="Plans without billing lock-in" subtitle="AtmosPath enforces SaaS-style entitlements now; payment collection is intentionally out of scope for this portfolio release.">
        <button className="button secondary" onClick={() => navigate("/usage")}>View usage</button>
        <button className="button primary" onClick={() => navigate("/map")}>Try the map</button>
      </PageTitle>
      <section className="pricing-grid">
        {plans.map((plan) => (
          <article className="surface pricing-card" key={plan.code}>
            <span className="eyebrow">{plan.code}</span>
            <h2>{plan.title}</h2>
            <strong>{plan.price}</strong>
            <p>{plan.detail}</p>
            <ul>{plan.limits.map((limit) => <li key={limit}><ShieldCheck size={15} /> {limit}</li>)}</ul>
            <button className={plan.code === "FREE" ? "button primary wide" : "button secondary wide"} onClick={() => navigate(plan.code === "FREE" ? "/directions" : "/usage")}>{plan.code === "FREE" ? "Start planning" : "Track readiness"}</button>
          </article>
        ))}
      </section>
    </main>
  );
}

export function AlertsPage({ navigate, national, weatherSnapshot = null, weatherRaster, dataStatus }: LiveProps & { weatherSnapshot?: NationalWeatherSnapshot | null }) {
  const { t } = useI18n();
  const [searchParams, setSearchParams] = useSearchParams();
  const query = searchParams.get("q") ?? "";
  const category = normalizeAlertCategory(searchParams.get("category"));
  const severeOnly = searchParams.get("severity") === "severe";
  const [savedRoutes, setSavedRoutes] = useState<SavedRouteRecord[]>([]);
  const userEmail = currentUser()?.email ?? null;
  useEffect(() => {
    if (!userEmail) return;
    void api.savedRoutes().then(setSavedRoutes).catch(() => setSavedRoutes([]));
  }, [userEmail]);
  const alerts = useMemo(() => {
    const live = national?.alerts ?? [];
    return live
      .filter((alert) => !severeOnly || ["Extreme", "Severe"].includes(alert.severity))
      .filter((alert) => category === "all" || alertCategory(alert) === category)
      .filter((alert) => alertMatchesQuery(alert, query));
  }, [category, national, query, severeOnly]);
  const relatedWeather = useMemo(() => weatherSearchResults(weatherSnapshot, query, category), [category, query, weatherSnapshot]);
  const [selectedId, setSelectedId] = useState("");
  useEffect(() => {
    if (!alerts.length) {
      setSelectedId("");
      return;
    }
    setSelectedId((current) => alerts.some((alert) => alert.alert_id === current) ? current : alerts[0].alert_id);
  }, [alerts]);
  const selected = alerts.find((alert) => alert.alert_id === selectedId) ?? alerts[0] ?? null;
  const impactedRoutes = useMemo(() => selected ? routeImpactsForAlert(selected, savedRoutes) : [], [savedRoutes, selected]);
  const hasAlertFilter = Boolean(query.trim()) || category !== "all" || severeOnly;
  const focusedAlerts = useMemo(() => selected ? [selected] : alerts, [alerts, selected]);
  const mapNational = useMemo(() => national ? {
    ...national,
    alerts: focusedAlerts,
    active_alerts: focusedAlerts.length,
    severe_alerts: focusedAlerts.filter((alert) => ["Extreme", "Severe"].includes(alert.severity)).length,
    alerts_with_geometry: focusedAlerts.filter((alert) => Boolean(alert.geometry)).length,
  } : null, [focusedAlerts, national]);
  const mapWeatherSnapshot = useMemo(() => {
    if (!hasAlertFilter || !weatherSnapshot) return weatherSnapshot;
    return { ...weatherSnapshot, points: relatedWeather };
  }, [hasAlertFilter, relatedWeather, weatherSnapshot]);
  const updateAlertSearch = (patch: { q?: string; category?: AlertCategory; severity?: "all" | "severe" }) => {
    const next = new URLSearchParams(searchParams);
    if (patch.q !== undefined) {
      const value = patch.q.trimStart();
      if (value) next.set("q", value); else next.delete("q");
    }
    if (patch.category !== undefined) {
      if (patch.category === "all") next.delete("category"); else next.set("category", patch.category);
    }
    if (patch.severity !== undefined) {
      if (patch.severity === "severe") next.set("severity", "severe"); else next.delete("severity");
    }
    setSearchParams(next);
  };

  return (
    <main className="alerts-page">
      <section className="alerts-main">
        <PageTitle title={t("alerts.title")} subtitle={t("alerts.subtitle")}><button className="button secondary" onClick={() => updateAlertSearch({ severity: severeOnly ? "all" : "severe" })}><SlidersHorizontal size={16} /> {severeOnly ? t("alerts.showAll") : t("alerts.severeOnly")}</button></PageTitle>
        <DataNotice status={dataStatus} hasData={Boolean(national)} />
        <div className="alert-command-bar">
          <label className="alert-search-field">
            <Search size={18} />
            <input value={query} onChange={(event) => updateAlertSearch({ q: event.target.value })} placeholder="Search flood, heat, Miami, I-95, county..." />
          </label>
          <div className="alert-category-tabs" aria-label="Alert categories">
            {alertCategoryOptions.map((option) => <button key={option} className={category === option ? "active" : ""} onClick={() => updateAlertSearch({ category: option })}>{alertCategoryLabel(option)}</button>)}
          </div>
        </div>
        <div className="alerts-workspace">
          <div className="alerts-list">
            <strong>{alerts.length} matching active alerts</strong>
            {alerts.map((alert) => <button className={selected?.alert_id === alert.alert_id ? "selected alert-card-rich" : "alert-card-rich"} key={alert.alert_id} onClick={() => setSelectedId(alert.alert_id)}>
              <AlertTriangle size={18} />
              <span><strong>{alert.event}</strong><small>{alert.area || "Affected U.S. region"}</small><em>{alertCategoryLabel(alertCategory(alert))} · {alert.urgency || "Unknown urgency"}</em></span>
              <i className={riskClass(alert.severity)}>{alert.severity}</i>
              <b>{alertDriverAction(alert)}</b>
              <ChevronRight size={15} />
            </button>)}
            {!alerts.length && <EmptyState title="No matching active alerts" detail="Try flood, heat, wind, a city, a county, or clear filters. We do not show fabricated warnings." />}
          </div>
          <div className="alerts-map"><RiskMapVisual national={mapNational} weatherSnapshot={mapWeatherSnapshot} weatherRaster={weatherRaster} regional />
            <div className="alert-weather-results">
              <strong>{selected ? `Focused map: ${selected.event}` : "Related weather signals"}</strong>
              {relatedWeather.length ? relatedWeather.map((point) => <button key={point.id} onClick={() => navigate(`/map?search=${encodeURIComponent(point.city)}`)}><span>{point.city}</span><small>{conditionLabel(point)} · {windLabel(point)}</small><em className={riskLevel(point.risk_score)}>{riskLevelLabel(point.risk_score)}</em></button>) : <small>No monitored weather point matches this search.</small>}
            </div>
          </div>
        </div>
      </section>
      <aside className="alert-inspector">
        {selected ? <>
          <div className="inspector-title"><AlertTriangle size={20} /><div><h2>{selected.event}</h2><span className={riskClass(selected.severity)}>{selected.severity}</span></div><Bookmark size={18} /></div>
          <div className="alert-action-card"><strong>{alertDriverAction(selected)}</strong><span>{alertOperationalSummary(selected)}</span></div>
          <InspectorMetric label="Certainty" value={selected.certainty || "Unknown"} />
          <InspectorMetric label="Urgency" value={selected.urgency || "Unknown"} />
          <InspectorMetric label="Category" value={alertCategoryLabel(alertCategory(selected))} />
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

function PlanBadge({ account }: { account: AccountSummary }) {
  return (
    <div className="plan-badge">
      <span>{account.plan.status}</span>
      <strong>{account.plan.code}</strong>
      <small>{account.plan.dispatchOptimizerEnabled ? "Optimizer enabled" : "Map preview quota"}</small>
    </div>
  );
}

function UsageMeter({ usage }: { usage: { label: string; used: number; limit: number; remaining: number; exceeded: boolean; resetsAt?: string } }) {
  const percent = usage.limit <= 0 ? 0 : Math.min(100, Math.round((usage.used / usage.limit) * 100));
  return (
    <article className={`usage-meter ${usage.exceeded ? "exceeded" : percent >= 80 ? "warning" : ""}`}>
      <div>
        <strong>{usage.label}</strong>
        <span>{usage.used.toLocaleString()} used / {usage.limit.toLocaleString()} limit</span>
      </div>
      <em>{usage.remaining.toLocaleString()} left</em>
      <i className={`meter-${Math.ceil(percent / 10) * 10}`}><b /></i>
    </article>
  );
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

function dashboardAlertResults(national: NationalRiskOverview | null, query: string, category: AlertCategory, limit: number) {
  return [...(national?.alerts ?? [])]
    .filter((alert) => category === "all" || alertCategory(alert) === category)
    .filter((alert) => alertMatchesQuery(alert, query))
    .sort((a, b) => b.score - a.score)
    .slice(0, limit);
}

const alertCategoryOptions: AlertCategory[] = ["all", "flood", "heat", "storm", "wind", "winter", "fire"];

function normalizeAlertCategory(value: string | null): AlertCategory {
  return alertCategoryOptions.includes(value as AlertCategory) ? value as AlertCategory : "all";
}

function alertCategory(alert: RiskAlert): AlertCategory {
  const text = `${alert.category ?? ""} ${alert.event} ${alert.headline}`.toLowerCase();
  if (text.includes("flood") || text.includes("coastal") || text.includes("river")) return "flood";
  if (text.includes("heat") || text.includes("excessive")) return "heat";
  if (text.includes("thunder") || text.includes("tornado") || text.includes("storm")) return "storm";
  if (text.includes("wind") || text.includes("gale") || text.includes("dust")) return "wind";
  if (text.includes("winter") || text.includes("snow") || text.includes("ice") || text.includes("blizzard")) return "winter";
  if (text.includes("fire") || text.includes("smoke") || text.includes("red flag")) return "fire";
  return "all";
}

function alertCategoryLabel(category: AlertCategory) {
  const labels: Record<AlertCategory, string> = {
    all: "All",
    flood: "Flood",
    heat: "Heat",
    storm: "Storm",
    wind: "Wind",
    winter: "Winter",
    fire: "Fire/smoke",
  };
  return labels[category];
}

function alertMatchesQuery(alert: RiskAlert, query: string) {
  const trimmed = query.trim().toLowerCase();
  if (!trimmed) return true;
  return [alert.event, alert.area, alert.headline, alert.instruction ?? "", alert.severity, alert.urgency, alertCategoryLabel(alertCategory(alert))]
    .join(" ")
    .toLowerCase()
    .includes(trimmed);
}

function alertDriverAction(alert: RiskAlert) {
  const category = alertCategory(alert);
  if (category === "flood") return "Avoid low roads";
  if (category === "heat") return "Plan cooling stops";
  if (category === "storm") return "Delay or reroute";
  if (category === "wind") return "Watch crosswinds";
  if (category === "winter") return "Avoid icy roads";
  if (category === "fire") return "Check smoke/fire";
  return alert.urgency === "Immediate" ? "Act now" : "Monitor";
}

function alertOperationalSummary(alert: RiskAlert) {
  const area = alert.area || "the affected area";
  const urgency = alert.urgency || "Unknown urgency";
  const certainty = alert.certainty || "unknown certainty";
  return `${urgency} alert for ${area}. Certainty: ${certainty}.`;
}

function weatherSearchResults(snapshot: NationalWeatherSnapshot | null, query: string, category: AlertCategory) {
  const normalized = query.trim().toLowerCase();
  return [...(snapshot?.points ?? [])]
    .filter((point) => point.data_status !== "UNAVAILABLE")
    .filter((point) => {
      const cityMatch = !normalized || point.city.toLowerCase().includes(normalized);
      if (cityMatch) return true;
      if (category === "flood" || category === "storm") return point.precipitation_probability >= 50;
      if (category === "heat") return point.temperature_f >= 90;
      if (category === "wind") return point.wind_speed_mph >= 20;
      if (category === "winter") return blackIceScore(point) >= 25;
      return false;
    })
    .sort((a, b) => b.risk_score - a.risk_score)
    .slice(0, 4);
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
  return `${conditionLabel(point)} · ${windLabel(point)} · ${Math.round(point.temperature_f)}°F`;
}

function conditionLabel(point: WeatherRisk) {
  if (point.precipitation_probability >= 80) return "Rain likely";
  if (point.precipitation_probability >= 55) return "Rain possible";
  if (point.precipitation_probability >= 25) return "Some moisture signal";
  return "No rain signal";
}

function roadImpactLabel(point: WeatherRisk) {
  if (point.risk_score >= 80) return "Avoid if possible";
  if (point.precipitation_probability >= 80 || point.wind_speed_mph >= 25) return "High caution";
  if (point.precipitation_probability >= 55 || point.wind_speed_mph >= 18) return "Slow down";
  if (blackIceScore(point) >= 25) return "Ice watch";
  return "Normal caution";
}

function windLabel(point: WeatherRisk) {
  const wind = Math.round(point.wind_speed_mph);
  if (wind >= 30) return `${wind} mph high wind`;
  if (wind >= 20) return `${wind} mph gusty`;
  if (wind >= 12) return `${wind} mph breeze`;
  return `${wind} mph light`;
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

function formatReset(value?: string) {
  if (!value || value === "capacity") return "when capacity changes";
  return new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", hour: "numeric", timeZoneName: "short" }).format(new Date(value));
}

function savePlace(place: (typeof places)[string], score?: number) {
  if (!currentUser()) {
    notify("Sign in to save this place to your private watchlist.");
    void login();
    return;
  }
  void api.savePlace(place, score).then(() => notify(`${place.city} saved to your account.`)).catch(() => notify("This place could not be saved."));
}
