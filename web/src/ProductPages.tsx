import {
  AlertTriangle,
  ArrowRight,
  Bell,
  Bookmark,
  ChevronRight,
  CloudRain,
  CloudSun,
  Droplets,
  ExternalLink,
  Filter,
  Folder,
  Gauge,
  Layers3,
  MapPin,
  Navigation,
  Plus,
  Route,
  Search,
  ShieldCheck,
  SlidersHorizontal,
  Wind,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";

import type { Navigate } from "./App";
import { api } from "./api";
import { currentUser, login } from "./auth";
import { changes, places, riskLevel, riskRows, savedItems } from "./mockData";
import type { LocationRisk, NationalRiskOverview, NationalWeatherSnapshot, RiskAlert, SavedPlaceRecord } from "./types";
import { notify } from "./ui";

export function HomePage({ navigate, national }: { navigate: Navigate; national: NationalRiskOverview | null }) {
  const [query, setQuery] = useState("");
  const submit = () => navigate(query.trim() ? `/map?search=${encodeURIComponent(query.trim())}` : "/directions");
  return (
    <main className="page-shell home-page">
      <section className="welcome-row">
        <div><span className="eyebrow">Good morning</span><h1>Good morning, Alex <CloudSun size={28} /></h1><p>Where do you need to go?</p></div>
      </section>
      <section className="home-actions">
        <label className="hero-search"><Search size={20} /><input value={query} onChange={(event) => setQuery(event.target.value)} onKeyDown={(event) => event.key === "Enter" && submit()} placeholder="Search for a city, address, highway, or route" /><SlidersHorizontal size={18} /></label>
        <QuickAction icon={<Navigation />} title="Plan a route" subtitle="Compare routes" onClick={() => navigate("/directions")} />
        <QuickAction icon={<Layers3 />} title="Explore risk" subtitle="View map layers" onClick={() => navigate("/map")} />
        <QuickAction icon={<Bookmark />} title="Check saved places" subtitle="See recent updates" onClick={() => navigate("/saved")} />
      </section>
      <section className="home-grid">
        <div className="surface outlook-card">
          <SectionHeader title="National outlook" meta={`Updated ${formatTime(national?.generated_at)}`} action="View full map" onAction={() => navigate("/map")} />
          <RiskMapVisual national={national} />
        </div>
        <div className="surface matters-card">
          <SectionHeader title="What matters today" action="View all alerts" onAction={() => navigate("/alerts")} />
          {topAlerts(national).map((alert, index) => <AlertStory key={alert.alert_id} alert={alert} index={index} onClick={() => navigate("/alerts")} />)}
        </div>
      </section>
      <section className="home-bottom">
        <div className="surface next-trip">
          <SectionHeader title="Your next trip" action="View all trips" onAction={() => navigate("/saved")} />
          <div className="trip-flow"><div><i /><strong>Atlanta, GA</strong><span /><i /><strong>Nashville, TN</strong></div><div><em>Lower risk recommended</em><strong>3 hr 28 min</strong><small>12 min longer · much lower weather risk</small><button className="button secondary" onClick={() => navigate("/directions")}>View route options <ChevronRight size={15} /></button></div></div>
        </div>
        <div className="surface recent-card">
          <SectionHeader title="Recently viewed" />
          <div className="recent-grid">{savedItems.slice(0, 4).map((item) => <button key={item.id} onClick={() => navigate(item.kind === "Place" ? `/locations/${item.id}` : "/directions")}><MapThumb seed={item.id} /><span><strong>{item.title}</strong><i className={riskLevel(item.risk)}>{item.level}</i></span><small>{item.meta}</small></button>)}</div>
        </div>
      </section>
    </main>
  );
}

export function DashboardPage({ navigate, national, weatherSnapshot }: { navigate: Navigate; national: NationalRiskOverview | null; weatherSnapshot: NationalWeatherSnapshot | null }) {
  const liveRows = useMemo(() => dashboardRiskRows(weatherSnapshot), [weatherSnapshot]);
  return (
    <main className="page-shell dashboard-page">
      <PageTitle title="Your risk dashboard" subtitle="3 saved items need attention">
        <button className="button secondary" onClick={() => navigate("/saved")}>Manage saved items</button>
        <button className="button primary" onClick={() => navigate("/directions")}>Plan a new route <Plus size={16} /></button>
      </PageTitle>
      <section className="dashboard-top">
        <div className="surface risk-matrix"><SectionHeader title="Risk across your saved items" meta="Live NOAA/NWS snapshot" /><div className="matrix-head"><span /><span>Now</span><span>+3h</span><span>+6h</span><span>Tomorrow</span></div>{liveRows.map((row) => <button key={row.name} onClick={() => navigate(row.kind === "City" ? `/locations/${row.name.split(",")[0].toLowerCase()}` : "/saved")}><span><strong>{row.name}</strong><small>{row.kind}</small></span>{row.values.map((value, index) => <i className={riskLevel(value)} key={index}>{value}</i>)}</button>)}</div>
        <div className="surface dashboard-map"><SectionHeader title="National outlook" action="Open full map" onAction={() => navigate("/map")} /><RiskMapVisual national={national} compact /></div>
      </section>
      <section className="dashboard-middle">
        <div className="surface change-feed"><SectionHeader title="What changed" meta="Since your last visit" action="View all" onAction={() => navigate("/alerts")} />{changes.map((change) => <button key={change.title} onClick={() => navigate(change.target.includes("Miami") ? "/locations/miami" : change.target.includes("Atlanta") ? "/locations/atlanta" : "/saved")}><i className={change.severity}><CloudRain size={18} /></i><span><strong>{change.title}</strong><small>{change.detail}</small></span><em>{change.from} → {change.to}</em><b>{change.source}</b><ChevronRight size={16} /></button>)}</div>
        <div className="surface departures"><SectionHeader title="Upcoming departures" action="View all" onAction={() => navigate("/saved")} /><h3>Atlanta, GA → Nashville, TN</h3><div className="departure-options"><RouteOption label="Fastest" time="3 hr 18 min" risk={54} /><RouteOption label="Lower weather risk" time="4 hr 2 min" risk={29} /></div><button className="button primary wide" onClick={() => navigate("/directions")}>Compare routes</button></div>
      </section>
      <section className="dashboard-bottom"><div className="surface forecast-panel"><SectionHeader title="Risk outlook for saved areas" action="See full outlook" onAction={() => navigate("/saved")} /><div className="forecast-grid">{liveRows.map((row) => <ForecastCard key={row.name} title={row.name} values={row.values.concat([row.values[3], row.values[3], row.values[3]])} onClick={() => navigate("/saved")} />)}</div></div><div className="surface alert-summary"><SectionHeader title="Alert summary" action="View all alerts" onAction={() => navigate("/alerts")} />{topAlerts(national).map((alert) => <button key={alert.alert_id} onClick={() => navigate("/alerts")}><AlertTriangle size={16} /><span>{alert.event}</span><strong>{alert.score}</strong><ChevronRight size={15} /></button>)}</div></section>
      <InterestGridPanel snapshot={weatherSnapshot} navigate={navigate} />
    </main>
  );
}

export function SavedPage({ navigate, weatherSnapshot }: { navigate: Navigate; weatherSnapshot: NationalWeatherSnapshot | null }) {
  const [tab, setTab] = useState("All");
  const [selectedId, setSelectedId] = useState(savedItems[0].id);
  const [query, setQuery] = useState("");
  const [highestRisk, setHighestRisk] = useState(false);
  const [personalPlaces, setPersonalPlaces] = useState<SavedPlaceRecord[]>([]);
  useEffect(() => {
    if (currentUser()) void api.savedPlaces().then(setPersonalPlaces).catch(() => setPersonalPlaces([]));
  }, []);
  const liveItems = useMemo(() => [
    ...personalPlaces.map((place) => ({
      id: place.savedItemId,
      kind: "Place",
      title: place.name,
      risk: place.currentRiskScore ?? 0,
      level: riskLevelLabel(place.currentRiskScore ?? 0),
      change: "Saved to your AtmosPath account",
      meta: "Personal saved place",
      action: "View on map",
    })),
    ...applyLiveSavedRisk(weatherSnapshot),
  ], [personalPlaces, weatherSnapshot]);
  const filtered = useMemo(() => {
    const byTab = tab === "All" ? liveItems : liveItems.filter((item) => item.kind === tab.slice(0, -1));
    const byQuery = byTab.filter((item) => item.title.toLowerCase().includes(query.toLowerCase()));
    return highestRisk ? [...byQuery].sort((a, b) => b.risk - a.risk) : byQuery;
  }, [highestRisk, liveItems, query, tab]);
  const selected = liveItems.find((item) => item.id === selectedId) ?? liveItems[0];
  return (
    <main className="saved-layout">
      <aside className="collections-panel"><h3>Collections <button onClick={() => notify("Collections are read-only in the public portfolio session.")}><Plus size={16} /></button></h3>{[["All saved", `${liveItems.length}`], ["Places", `${liveItems.filter((item) => item.kind === "Place").length}`], ["Routes", `${liveItems.filter((item) => item.kind === "Route").length}`], ["Corridors", `${liveItems.filter((item) => item.kind === "Corridor").length}`]].map(([name, count]) => <button className={tab === (name === "All saved" ? "All" : name) ? "active" : ""} onClick={() => setTab(name === "All saved" ? "All" : name)} key={name}><Folder size={17} /><span>{name}</span><em>{count}</em></button>)}<div className="collection-insight"><strong>Insights</strong><span>High-risk items <b>{liveItems.filter((item) => item.risk >= 55).length}</b></span><span>Live weather coverage <b>{Math.round((weatherSnapshot?.coverage ?? 0) * 100)}%</b></span></div></aside>
      <section className="saved-main"><PageTitle title="Saved places & routes" subtitle="Monitor what matters before conditions change"><button className="button primary" onClick={() => navigate("/map")}><Plus size={16} /> Add from map</button></PageTitle><div className="tab-row">{["All", "Places", "Routes", "Corridors"].map((name) => <button className={tab === name ? "active" : ""} onClick={() => setTab(name)} key={name}>{name}</button>)}</div><div className="saved-toolbar"><label><Search size={17} /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search saved places & routes..." /></label><button className={highestRisk ? "active" : ""} onClick={() => setHighestRisk((value) => !value)}><Filter size={16} /> Highest risk</button></div><div className="saved-grid">{filtered.map((item) => <button key={item.id} className={selected.id === item.id ? "selected" : ""} onClick={() => setSelectedId(item.id)}><MapThumb seed={item.id} /><span className="saved-card-title"><MapPin size={15} /><strong>{item.title}</strong></span><span className="saved-risk"><b className={riskLevel(item.risk)}>{item.risk}</b><i>{item.level} risk</i><small>{item.change}</small></span><span className="saved-meta"><Bell size={13} /> Alerts on · {item.meta}</span><span className="saved-actions"><i onClick={(event) => { event.stopPropagation(); navigate(item.kind === "Place" ? `/locations/${item.id}` : "/directions"); }}>{item.action}</i><i onClick={(event) => { event.stopPropagation(); navigate("/alerts"); }}>Manage alerts</i></span></button>)}</div><InterestGridPanel snapshot={weatherSnapshot} navigate={navigate} compact /></section>
      <aside className="saved-inspector"><div className="inspector-title"><MapPin size={18} /><div><h2>{selected.title}</h2><span>{selected.kind}</span></div><Bookmark size={18} /></div><MapThumb seed={`${selected.id}-large`} large /><div className="selected-risk"><b className={riskLevel(selected.risk)}>{selected.risk}</b><span><strong>{selected.level} risk</strong><small>Current composite score</small></span><em>↑ 18</em></div><h4>Next material change</h4><div className="material-change"><CloudRain size={20} /><span><strong>{selected.change}</strong><small>Conditions are expected to change soon.</small></span></div><h4>Active hazards</h4>{["Severe thunderstorms", "Flash flood watch", "High wind advisory"].map((name, index) => <div className="hazard-row" key={name}><AlertTriangle size={15} /><span>{name}</span><i className={index === 0 ? "high" : "moderate"}>{index === 0 ? "High" : "Moderate"}</i></div>)}<button className="button primary wide" onClick={() => navigate(selected.kind === "Place" ? `/locations/${selected.id}` : "/directions")}>Open {selected.kind.toLowerCase()}</button><button className="button secondary wide" onClick={() => navigate("/directions")}>Plan route from here</button></aside>
    </main>
  );
}

export function AlertsPage({ navigate, national }: { navigate: Navigate; national: NationalRiskOverview | null }) {
  const [tab, setTab] = useState("For you");
  const [severeOnly, setSevereOnly] = useState(false);
  const alerts = useMemo(() => {
    const all = national?.alerts.length ? national.alerts : topAlerts(null);
    const scoped = tab === "Nationwide" ? all : tab === "Along saved routes" ? all.filter((alert) => /I-|Atlanta|Miami|Seattle/i.test(`${alert.area} ${alert.headline}`)) : all.slice(0, 12);
    return severeOnly ? scoped.filter((alert) => ["Extreme", "Severe"].includes(alert.severity)) : scoped;
  }, [national, severeOnly, tab]);
  const [selected, setSelected] = useState<RiskAlert>(alerts[0]);
  useEffect(() => { if (alerts[0]) setSelected(alerts[0]); }, [alerts]);
  return (
    <main className="alerts-page">
      <section className="alerts-main"><PageTitle title="Alerts" subtitle="Hazards prioritized by their impact on you"><button className="button secondary" onClick={() => notify("Public portfolio mode uses NWS defaults; personal notification settings require sign-in.")}><SlidersHorizontal size={16} /> Alert settings</button></PageTitle><div className="tab-row">{["For you", "Along saved routes", "Nationwide"].map((name) => <button className={tab === name ? "active" : ""} onClick={() => setTab(name)} key={name}>{name}</button>)}</div><div className="alerts-workspace"><div className="alerts-list"><div className="filter-row"><button className={severeOnly ? "active" : ""} onClick={() => setSevereOnly((value) => !value)}>{severeOnly ? "Severe only" : "All severities"} <ChevronRight size={14} /></button><button onClick={() => notify("Hazard category filters are available through the live NWS alert list.")}>All hazards <ChevronRight size={14} /></button><button onClick={() => notify("Showing currently active NWS alerts.")}>Active now <ChevronRight size={14} /></button></div><strong>{alerts.length} active alerts</strong>{alerts.map((alert) => <button className={selected.alert_id === alert.alert_id ? "selected" : ""} key={alert.alert_id} onClick={() => setSelected(alert)}><AlertTriangle size={18} /><span><strong>{alert.event}</strong><small>{alert.area || "Affected U.S. region"}</small><em>NWS · Live alert</em></span><i className={riskClass(alert.severity)}>{alert.severity}</i><ChevronRight size={15} /></button>)}</div><div className="alerts-map"><RiskMapVisual national={national} regional /><div className="map-timeline"><button onClick={() => notify("Forecast playback is not enabled for the active-alert layer.")}>▶</button><span><i /><i /><i /><i /><i /></span><strong>Now</strong><small>Active NWS alert window</small></div></div></div></section>
      <aside className="alert-inspector"><div className="inspector-title"><AlertTriangle size={20} /><div><h2>{selected.event}</h2><span className={riskClass(selected.severity)}>{selected.severity}</span></div><Bookmark size={18} /></div><InspectorMetric label="Confidence" value={selected.certainty || "High"} /><InspectorMetric label="Active" value="Today, 10:30 AM – 1:30 PM ET" /><h4>What's happening</h4><p>{selected.headline || "Hazardous conditions may affect travel along saved routes."}</p><h4>Evidence</h4><div className="evidence-grid"><span><CloudRain />Radar<small>Heavy rainfall</small></span><span><Droplets />Rainfall<small>3–6 in</small></span><span><Gauge />Model<small>High runoff risk</small></span></div><h4>Affected saved items</h4><div className="affected-item"><Route size={16} /><span><strong>I-95 Corridor</strong><small>363 mi affected</small></span><em>High impact</em></div><div className="affected-item"><MapPin size={16} /><span><strong>Miami, FL</strong><small>Saved city</small></span><em>High impact</em></div><h4>Recommended action</h4><p className="recommendation"><ShieldCheck size={18} /> Consider delaying travel or taking a lower-risk route.</p><button className="button primary wide" onClick={() => navigate("/directions")}>View route alternatives <ArrowRight size={16} /></button><button className="button secondary wide" onClick={() => navigate("/map")}>Open on map <ExternalLink size={15} /></button></aside>
    </main>
  );
}

export function PlaceDetailPage({ navigate, slug }: { navigate: Navigate; slug: string }) {
  const [risk, setRisk] = useState<LocationRisk | null>(null);
  const place = places[slug] ?? places.miami;
  useEffect(() => {
    const requestedPlace = places[slug] ?? places.miami;
    setRisk(null);
    void api.locationRisk(requestedPlace).then(setRisk).catch(() => setRisk(null));
  }, [slug]);
  const score = risk?.score ?? 74;
  const reasons: Array<{ name: string; contribution: number; icon: React.ElementType }> = [
    { name: "Heavy rain", contribution: risk?.factors.precipitation ?? 40, icon: CloudRain },
    { name: "Flash flooding", contribution: risk?.factors.flood ?? 25, icon: Droplets },
    { name: "Wind", contribution: risk?.factors.wind ?? 9, icon: Wind },
    { name: "Active alerts", contribution: risk?.factors.active_alerts ?? 32, icon: AlertTriangle },
  ];
  return (
    <main className="page-shell place-page">
      <button className="back-link" onClick={() => navigate("/map")}>← Back to map</button>
      <section className="place-heading"><div><h1>{place.city}, {place.state}</h1><span>Last updated {formatTime(risk?.generated_at)}</span></div><Metric label="Composite risk" value={`${score}`} meta={risk?.level ?? "High"} tone={riskLevel(score)} /><Metric label="Confidence" value="High" meta="Live data" tone="low" /><Metric label="Current weather" value={`${Math.round(risk?.weather.temperature_f ?? 74)}°F`} meta={risk?.weather.precipitation_probability ? "Heavy rain" : "Cloudy"} tone="moderate" /><div className="place-actions"><button className="button primary" onClick={() => navigate("/directions")}>Plan a route</button><button className="button secondary" onClick={() => {
        if (!currentUser()) {
          notify("Sign in to save this place to your account.");
          void login();
          return;
        }
        void api.savePlace(place, score).then(() => notify(`${place.city} saved to your account.`)).catch(() => notify("This place could not be saved."));
      }}><Bookmark size={15} /> Save place</button><button className="button secondary" onClick={() => navigate("/alerts")}>Manage alerts</button></div></section>
      <section className="place-top"><div className="surface place-map"><RiskMapVisual regional /><div className="layer-box"><strong>Map layers</strong><span><Droplets /> Precipitation <b>✓</b></span><span><CloudRain /> Flood risk <b>✓</b></span><span><Wind /> Wind</span><span><AlertTriangle /> Active alerts <b>✓</b></span></div><Timeline score={score} /></div><div className="surface why-risk"><SectionHeader title="Why risk is high" action="Risk calculation" onAction={() => notify("Score combines precipitation, wind, heat, flood exposure, and active NWS alerts.")} />{reasons.map(({ name, contribution, icon: Icon }) => <div className="risk-reason" key={name}><Icon size={22} /><span><strong>{name}</strong><small>{name === "Heavy rain" ? "Rainfall rate and duration" : "Current conditions and alerts"}</small></span><em className={contribution >= 30 ? "high" : "moderate"}>{contribution >= 30 ? "High" : "Moderate"}</em><b>{contribution}<small>/100</small></b></div>)}<p>Risk combines hazards, exposure, and local sensitivity.</p></div></section>
      <section className="place-bottom"><div className="surface seven-day"><SectionHeader title="7-day risk forecast" action="Forecast methodology" onAction={() => notify("The public preview currently exposes live conditions and a directional risk outlook, not a certified seven-day forecast.")} /><div className="forecast-grid">{["Today", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"].map((day, index) => <div key={day}><strong>{day}</strong><CloudRain size={20} /><i className={riskLevel([score, 69, 46, 32, 28, 31, 35][index])}>{[score, 69, 46, 32, 28, 31, 35][index]}</i><small>{index < 2 ? "High" : "Improving"}</small></div>)}</div></div><div className="surface active-place-alerts"><SectionHeader title="Active alerts" action="View all alerts" onAction={() => navigate("/alerts")} />{(risk?.alerts.length ? risk.alerts : topAlerts(null)).slice(0, 3).map((alert) => <button key={alert.alert_id} onClick={() => navigate("/alerts")}><AlertTriangle size={16} /><span>{alert.event}</span><small>{alert.area || `${place.city} area`}</small><ChevronRight size={15} /></button>)}</div><div className="surface common-routes"><SectionHeader title={`Common routes from ${place.city}`} action="Compare routes" onAction={() => navigate("/directions")} />{commonRoutes(place.city).map(([name, time, routeRisk]) => <button key={name} onClick={() => navigate("/directions")}><strong>{name}</strong><span>{time}</span><i className={riskLevel(routeRisk)}>{routeRisk}</i><ChevronRight size={15} /></button>)}</div></section>
      <section className="context-row"><div className="surface live-context"><strong>Live conditions</strong><span><CloudRain /> {Math.round(risk?.weather.temperature_f ?? 74)}°F</span><span><Droplets /> {risk?.weather.precipitation_probability ?? 85}% rain</span><span><Wind /> {Math.round(risk?.weather.wind_speed_mph ?? 24)} mph</span></div><div className="surface long-context"><strong>Long-term context <small>(not a live warning)</small></strong><span><ShieldCheck /> FEMA National Risk Index: Relatively High</span><button onClick={() => navigate("/map")}>View full risk profile <ArrowRight size={15} /></button></div></section>
    </main>
  );
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

function RiskMapVisual({ national, compact, regional }: { national?: NationalRiskOverview | null; compact?: boolean; regional?: boolean }) {
  return <div className={`risk-map-visual ${compact ? "compact" : ""} ${regional ? "regional" : ""}`}><div className="weather-field field-one" /><div className="weather-field field-two" /><div className="weather-field field-three" /><span className="map-city city-one">Seattle</span><span className="map-city city-two">Atlanta</span><span className="map-city city-three">Miami</span><div className="map-alert alert-one">1</div><div className="map-alert alert-two">2</div><div className="map-alert alert-three">3</div><div className="risk-legend"><strong>Risk level</strong><i /><span>Low</span><span>Extreme</span></div>{national && <small className="map-status">{national.active_alerts} active alerts · {national.severe_alerts} severe</small>}</div>;
}

function InterestGridPanel({ snapshot, navigate, compact }: { snapshot: NationalWeatherSnapshot | null; navigate: Navigate; compact?: boolean }) {
  const points = useMemo(
    () => [...(snapshot?.points ?? [])].filter((point) => point.data_status !== "UNAVAILABLE").sort((a, b) => b.risk_score - a.risk_score).slice(0, compact ? 6 : 12),
    [compact, snapshot],
  );
  return <section className={`surface interest-grid ${compact ? "compact" : ""}`}><SectionHeader title="Live city & Interstate monitor" meta={`${snapshot?.points.length ?? 0} NOAA/NWS points · ${Math.round((snapshot?.coverage ?? 0) * 100)}% live`} action="Open national map" onAction={() => navigate("/map")} /><div>{points.map((point) => <button key={point.id} onClick={() => navigate("/map")}><span><strong>{point.city}</strong><small>{Math.round(point.temperature_f)}°F · {Math.round(point.wind_speed_mph)} mph · {Math.round(point.precipitation_probability)}% rain</small></span><i className={riskLevel(point.risk_score)}>{point.risk_score}</i></button>)}</div></section>;
}

function AlertStory({ alert, index, onClick }: { alert: RiskAlert; index: number; onClick: () => void }) {
  return <button className="alert-story" onClick={onClick}><i className={riskClass(alert.severity)}>{index + 1}</i><span><strong>{alert.event}</strong><small>{alert.area || "Affected U.S. region"}</small><p>{alert.headline}</p><em>Affects {index + 1} saved {index ? "place" : "routes"}</em></span><b className={riskClass(alert.severity)}>{alert.severity}</b><ChevronRight size={16} /></button>;
}

function RouteOption({ label, time, risk }: { label: string; time: string; risk: number }) {
  return <div><span><strong>{label}</strong><em>{time}</em><small>Risk <i className={riskLevel(risk)}>{risk}</i></small></span><MapThumb seed={label} /></div>;
}

function ForecastCard({ title, values, onClick }: { title: string; values: number[]; onClick: () => void }) {
  return <button onClick={onClick}><strong>{title}</strong><span>{values.map((value, index) => <i className={riskLevel(value)} key={index}>{value}</i>)}</span><small>{Math.max(...values) >= 70 ? "High risk period expected" : "Mostly manageable conditions"}</small></button>;
}

function MapThumb({ seed, large }: { seed: string; large?: boolean }) {
  const route = seed.includes("route") || seed.includes("nashville") || seed.includes("miami");
  return <div className={`map-thumb ${large ? "large" : ""}`}><span className={`thumb-weather ${riskLevel(seed.length * 7 % 90)}`} />{route && <i className="thumb-route" />}<MapPin size={large ? 22 : 16} /></div>;
}

function InspectorMetric({ label, value }: { label: string; value: string }) {
  return <div className="inspector-metric"><span>{label}</span><strong>{value}</strong></div>;
}

function Metric({ label, value, meta, tone }: { label: string; value: string; meta: string; tone: string }) {
  return <div className="place-metric"><span>{label}</span><strong className={tone}>{value}</strong><small>{meta}</small></div>;
}

function Timeline({ score }: { score: number }) {
  return <div className="risk-timeline"><strong>24-hour risk timeline</strong><div>{Array.from({ length: 44 }, (_, index) => <i key={index} className={riskLevel(index < 10 ? score : index < 24 ? 38 : 68)} />)}</div><span><small>Now</small><small>12 PM</small><small>6 PM</small><small>12 AM</small><small>6 AM</small></span></div>;
}

function dashboardRiskRows(snapshot: NationalWeatherSnapshot | null) {
  const cityNames = ["Atlanta", "Miami", "Seattle"];
  const rows = cityNames.map((city) => {
    const point = snapshot?.points.find((candidate) => candidate.city.toLowerCase().includes(city.toLowerCase()));
    const score = point?.risk_score ?? riskRows.find((row) => row.name.startsWith(city))?.values[0] ?? 0;
    return { name: `${city}, ${places[city.toLowerCase()]?.state ?? ""}`, kind: "City", values: [score, score, score, score] };
  });
  const corridorPoints = snapshot?.points.filter((point) => point.city.includes("I-95")) ?? [];
  const corridorScore = corridorPoints.length ? Math.max(...corridorPoints.map((point) => point.risk_score)) : 0;
  return [...rows, { name: "I-95 Corridor", kind: "Corridor · live samples", values: [corridorScore, corridorScore, corridorScore, corridorScore] }];
}

function applyLiveSavedRisk(snapshot: NationalWeatherSnapshot | null) {
  return savedItems.map((item) => {
    const city = item.kind === "Place" ? item.title.split(",")[0] : item.id === "i95" ? "I-95" : "";
    const matching = city ? snapshot?.points.filter((point) => point.city.toLowerCase().includes(city.toLowerCase())) ?? [] : [];
    if (!matching.length) return item;
    const risk = Math.max(...matching.map((point) => point.risk_score));
    const level = risk >= 80 ? "Severe" : risk >= 55 ? "High" : risk >= 30 ? "Moderate" : "Low";
    return { ...item, risk, level, meta: `Live NOAA/NWS · ${formatTime(snapshot?.generated_at)}` };
  });
}

function riskLevelLabel(score: number) {
  if (score >= 80) return "Severe";
  if (score >= 55) return "High";
  if (score >= 30) return "Moderate";
  return "Low";
}

function topAlerts(national: NationalRiskOverview | null): RiskAlert[] {
  if (national?.alerts.length) return national.alerts.slice(0, 3);
  return [
    { alert_id: "flood", event: "Flash Flood Warning", severity: "Severe", urgency: "Immediate", certainty: "High", headline: "Heavy rain may cause dangerous flooding along I-95.", area: "Miami-Dade County", score: 92 },
    { alert_id: "storm", event: "Severe Thunderstorm Watch", severity: "Severe", urgency: "Expected", certainty: "Likely", headline: "Damaging winds and large hail possible.", area: "Atlanta to Nashville", score: 82 },
    { alert_id: "wind", event: "High Wind Watch", severity: "Moderate", urgency: "Future", certainty: "Possible", headline: "Strong crosswinds may affect high-profile vehicles.", area: "Central Plains", score: 58 },
  ];
}

function riskClass(level?: string) {
  return (level ?? "low").toLowerCase();
}

function formatTime(value?: string) {
  if (!value) return "just now";
  return new Intl.DateTimeFormat("en-US", { hour: "numeric", minute: "2-digit", timeZoneName: "short" }).format(new Date(value));
}

function commonRoutes(city: string): Array<[string, string, number]> {
  if (city === "Atlanta") return [["Nashville, TN", "Today, 1 PM", 42], ["Miami, FL", "Today, 2 PM", 64], ["I-75 North", "Today, 12 PM", 38]];
  if (city === "Seattle") return [["Portland, OR", "Today, 1 PM", 28], ["Spokane, WA", "Today, 2 PM", 35], ["I-90 East", "Today, 12 PM", 31]];
  return [["Atlanta, GA", "Today, 1 PM", 64], ["Orlando, FL", "Today, 2 PM", 45], ["I-95 North", "Today, 12 PM", 48]];
}
