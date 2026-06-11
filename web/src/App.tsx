import {
  Bell,
  Bookmark,
  ChevronDown,
  CloudSun,
  Home,
  LayoutDashboard,
  Map,
  ShieldAlert,
} from "lucide-react";
import { useEffect, useState } from "react";
import "maplibre-gl/dist/maplibre-gl.css";

import { api } from "./api";
import { MapPage } from "./MapPage";
import { AlertsPage, DashboardPage, HomePage, PlaceDetailPage, SavedPage } from "./ProductPages";
import type { NationalRiskOverview, NationalWeatherSnapshot, WeatherRasterManifest } from "./types";
import { notify } from "./ui";
import "./styles.css";

export type Navigate = (path: string) => void;

const navItems = [
  { path: "/", label: "Home", icon: Home },
  { path: "/map", label: "Map", icon: Map },
  { path: "/dashboard", label: "Dashboard", icon: LayoutDashboard },
  { path: "/saved", label: "Saved", icon: Bookmark },
  { path: "/alerts", label: "Alerts", icon: ShieldAlert },
];

function App() {
  const [path, setPath] = useState(window.location.pathname);
  const [nationalRisk, setNationalRisk] = useState<NationalRiskOverview | null>(null);
  const [weatherSnapshot, setWeatherSnapshot] = useState<NationalWeatherSnapshot | null>(null);
  const [weatherRaster, setWeatherRaster] = useState<WeatherRasterManifest | null>(null);
  const [toast, setToast] = useState("");

  useEffect(() => {
    const onPopState = () => setPath(window.location.pathname);
    window.addEventListener("popstate", onPopState);
    return () => window.removeEventListener("popstate", onPopState);
  }, []);

  useEffect(() => {
    void api.nationalRisk().then(setNationalRisk).catch(() => setNationalRisk(null));
    void api.weatherSnapshot().then(setWeatherSnapshot).catch(() => setWeatherSnapshot(null));
    void api.weatherRaster().then(setWeatherRaster).catch(() => setWeatherRaster(null));
  }, []);

  useEffect(() => {
    const onToast = (event: Event) => {
      setToast((event as CustomEvent<string>).detail);
      window.setTimeout(() => setToast(""), 3000);
    };
    window.addEventListener("atmospath:toast", onToast);
    return () => window.removeEventListener("atmospath:toast", onToast);
  }, []);

  const navigate: Navigate = (nextPath) => {
    const nextUrl = new URL(nextPath, window.location.origin);
    if (`${nextUrl.pathname}${nextUrl.search}` !== `${window.location.pathname}${window.location.search}`) window.history.pushState({}, "", nextUrl);
    setPath(nextUrl.pathname);
    window.scrollTo({ top: 0, behavior: "smooth" });
  };

  return (
    <div className={`product-app ${path === "/map" || path === "/directions" ? "map-active" : ""}`}>
      <AppHeader path={path} navigate={navigate} />
      {path === "/" && <HomePage navigate={navigate} national={nationalRisk} />}
      {path === "/dashboard" && <DashboardPage navigate={navigate} national={nationalRisk} weatherSnapshot={weatherSnapshot} />}
      {path === "/saved" && <SavedPage navigate={navigate} weatherSnapshot={weatherSnapshot} />}
      {path === "/alerts" && <AlertsPage navigate={navigate} national={nationalRisk} />}
      {path.startsWith("/locations/") && <PlaceDetailPage navigate={navigate} slug={path.split("/").pop() ?? "miami"} />}
      {(path === "/map" || path === "/directions") && <MapPage navigate={navigate} national={nationalRisk} weatherSnapshot={weatherSnapshot} weatherRaster={weatherRaster} />}
      {!["/", "/dashboard", "/saved", "/alerts", "/map", "/directions"].includes(path) && !path.startsWith("/locations/") && (
        <NotFound navigate={navigate} />
      )}
      {toast && <div className="app-toast" role="status">{toast}</div>}
    </div>
  );
}

function AppHeader({ path, navigate }: { path: string; navigate: Navigate }) {
  const activePath = path === "/directions" || path.startsWith("/locations/") ? "/map" : path;
  return (
    <header className="app-header">
      <button className="wordmark" onClick={() => navigate("/")} aria-label="AtmosPath home">
        <span className="peach-mark"><i /><b /></span>
        <span><strong>AtmosPath</strong><small>Weather-aware navigation</small></span>
      </button>
      <nav className="primary-nav" aria-label="Primary navigation">
        {navItems.map(({ path: itemPath, label, icon: Icon }) => (
          <button key={itemPath} className={activePath === itemPath ? "active" : ""} onClick={() => navigate(itemPath)}>
            <Icon size={18} /><span>{label}</span>{label === "Alerts" && <em>3</em>}
          </button>
        ))}
      </nav>
      <div className="header-tools">
        <button className="weather-chip" onClick={() => navigate("/locations/atlanta")}><CloudSun size={20} /><span><strong>72°F</strong><small>Atlanta, GA</small></span></button>
        <button className="icon-button" aria-label="Notifications" onClick={() => navigate("/alerts")}><Bell size={19} /></button>
        <button className="avatar-button" onClick={() => notify("Guest portfolio session. Account sign-in is not enabled.")}>AB</button>
        <ChevronDown size={15} />
      </div>
    </header>
  );
}

function NotFound({ navigate }: { navigate: Navigate }) {
  return (
    <main className="page-shell empty-page">
      <Map size={36} />
      <h1>This route is not on the map yet.</h1>
      <p>Return home or open the live nationwide risk map.</p>
      <div><button className="button secondary" onClick={() => navigate("/")}>Go home</button><button className="button primary" onClick={() => navigate("/map")}>Open map</button></div>
    </main>
  );
}

export default App;
