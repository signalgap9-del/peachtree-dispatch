import {
  AlertTriangle,
  ArrowRight,
  ChevronDown,
  CloudRain,
  Command,
  Gauge,
  Layers3,
  LocateFixed,
  MapPin,
  Navigation,
  RefreshCw,
  Route,
  Search,
  Settings2,
  Sparkles,
  TimerReset,
  Truck,
  Wind,
  X,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import "maplibre-gl/dist/maplibre-gl.css";

import { api } from "./api";
import { NetworkMap } from "./NetworkMap";
import type { NetworkOverview, OptimizedRoute, WeatherRisk } from "./types";
import "./styles.css";

function App() {
  const [network, setNetwork] = useState<NetworkOverview | null>(null);
  const [selectedRoute, setSelectedRoute] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showWeather, setShowWeather] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await api.network();
      setNetwork(result);
      setSelectedRoute((current) => current ?? result.routes[0]?.route_id ?? null);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Unable to load network intelligence");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const selected = useMemo(
    () => network?.routes.find((route) => route.route_id === selectedRoute) ?? null,
    [network, selectedRoute],
  );
  const highestRisk = useMemo(
    () => [...(network?.weather ?? [])].sort((a, b) => b.risk_score - a.risk_score)[0],
    [network],
  );

  return (
    <div className="map-app">
      <NetworkMap network={showWeather ? network : network && { ...network, weather: [] }} selectedRoute={selectedRoute} onSelectRoute={setSelectedRoute} />

      <header className="topbar glass">
        <div className="wordmark"><span><Route size={20} /></span><strong>Peachtree</strong><small>CLIMATE ROUTING</small></div>
        <label className="map-search"><Search size={17} /><input placeholder="Search a route, driver, or Atlanta destination" /><kbd>⌘ K</kbd></label>
        <div className="top-actions">
          <button className={`layer-toggle ${showWeather ? "active" : ""}`} onClick={() => setShowWeather(!showWeather)}><CloudRain size={16} />Climate layer</button>
          <button className="icon-button"><Settings2 size={17} /></button>
          <div className="profile">YO</div>
        </div>
      </header>

      <aside className="control-panel glass">
        <div className="panel-heading">
          <div><span className="kicker">LIVE OPTIMIZATION</span><h1>Atlanta network</h1></div>
          <button className="icon-button" onClick={() => void load()}><RefreshCw className={loading ? "spin" : ""} size={16} /></button>
        </div>
        <p className="intro">Routes adapt to rain, wind, promised windows, and current road geometry.</p>

        <div className="optimizer-status">
          <div className="pulse"><Sparkles size={15} /></div>
          <div><strong>Climate-aware optimizer</strong><span>{network?.algorithm ?? "Calculating network…"}</span></div>
          <ChevronDown size={15} />
        </div>

        <div className="network-stats">
          <Stat icon={<Navigation />} label="Network miles" value={network?.total_distance_miles.toFixed(0) ?? "—"} />
          <Stat icon={<TimerReset />} label="Risk minutes saved" value={network?.avoided_risk_minutes.toFixed(0) ?? "—"} />
          <Stat icon={<Truck />} label="Active routes" value={network?.routes.length.toString() ?? "—"} />
        </div>

        <div className="section-title"><span>Optimized routes</span><small>{network?.routes.length ?? 0} LIVE</small></div>
        <div className="route-list">
          {network?.routes.map((route) => (
            <RouteCard key={route.route_id} route={route} active={route.route_id === selectedRoute} onClick={() => setSelectedRoute(route.route_id)} />
          ))}
        </div>
      </aside>

      {showWeather && highestRisk && <WeatherCard weather={highestRisk} />}

      <div className="map-tools glass">
        <button title="Recenter Atlanta"><LocateFixed size={18} /></button>
        <button title="Map layers"><Layers3 size={18} /></button>
        <button title="Network performance"><Gauge size={18} /></button>
      </div>

      {selected && (
        <section className="route-dock glass">
          <div className="route-color" style={{ background: selected.color }} />
          <div className="dock-driver"><div className="driver-avatar"><Truck size={18} /></div><div><span>{selected.route_id}</span><strong>{selected.driver_id}</strong></div></div>
          <div className="dock-route"><MapPin size={16} /><div><span>Optimized sequence</span><strong>{selected.delivery_ids.join("  →  ")}</strong></div></div>
          <DockMetric label="Drive time" value={`${Math.round(selected.duration_minutes)} min`} />
          <DockMetric label="Climate buffer" value={`+${Math.round(selected.climate_delay_minutes)} min`} alert={selected.risk_score >= 45} />
          <DockMetric label="Distance" value={`${selected.distance_miles.toFixed(1)} mi`} />
          <button className="dispatch-button">Dispatch route <ArrowRight size={16} /></button>
          <button className="dock-close" onClick={() => setSelectedRoute(null)}><X size={16} /></button>
        </section>
      )}

      {error && <div className="map-error"><AlertTriangle size={16} />{error}</div>}
      <div className="map-attribution-note">Live forecast: Open-Meteo · Routing: OSRM · Map data: OpenStreetMap</div>
    </div>
  );
}

function RouteCard({ route, active, onClick }: { route: OptimizedRoute; active: boolean; onClick: () => void }) {
  return (
    <button className={`route-card ${active ? "active" : ""}`} onClick={onClick}>
      <i style={{ background: route.color }} />
      <div className="route-main"><span>{route.route_id}</span><strong>{route.driver_id}</strong><small>{route.optimization_note}</small></div>
      <div className="route-score"><strong>{route.risk_score}</strong><span>RISK</span></div>
    </button>
  );
}

function Stat({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return <div className="network-stat">{icon}<strong>{value}</strong><span>{label}</span></div>;
}

function DockMetric({ label, value, alert }: { label: string; value: string; alert?: boolean }) {
  return <div className={`dock-metric ${alert ? "alert" : ""}`}><span>{label}</span><strong>{value}</strong></div>;
}

function WeatherCard({ weather }: { weather: WeatherRisk }) {
  return (
    <aside className="weather-card glass">
      <div className="weather-top"><div className={`risk-orb ${weather.risk_level.toLowerCase()}`}><CloudRain size={19} /></div><div><span>Highest route risk</span><strong>{weather.city}, GA</strong></div><em>{weather.risk_level}</em></div>
      <div className="weather-values">
        <div><CloudRain size={14} /><span>Rain probability</span><strong>{weather.precipitation_probability}%</strong></div>
        <div><Wind size={14} /><span>Wind</span><strong>{weather.wind_speed_mph.toFixed(0)} mph</strong></div>
        <div><Command size={14} /><span>Temperature</span><strong>{weather.temperature_f.toFixed(0)}°F</strong></div>
      </div>
      <p>Optimizer places lower-risk stops ahead of this forecast window.</p>
    </aside>
  );
}

export default App;
