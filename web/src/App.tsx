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
import { authConfigured, completeLogin, currentUser, login, logout, type AuthUser } from "./auth";
import { MapPage } from "./MapPage";
import { AlertsPage, DashboardPage, HomePage, PlaceDetailPage, SavedPage } from "./ProductPages";
import type { NationalRiskOverview, NationalWeatherSnapshot, WeatherRasterManifest } from "./types";
import { notify } from "./ui";
import "./styles.css";

export type Navigate = (path: string) => void;
export type DataStatus = "loading" | "ready" | "degraded";

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
  const [user, setUser] = useState<AuthUser | null>(() => currentUser());
  const [dataStatus, setDataStatus] = useState<DataStatus>("loading");

  useEffect(() => {
    void completeLogin()
      .then((completed) => { if (completed) setUser(currentUser()); })
      .catch(() => notify("Sign-in could not be completed."));
  }, []);

  useEffect(() => {
    const onPopState = () => setPath(window.location.pathname);
    window.addEventListener("popstate", onPopState);
    return () => window.removeEventListener("popstate", onPopState);
  }, []);

  useEffect(() => {
    void Promise.allSettled([
      api.nationalRisk().then(setNationalRisk),
      api.weatherSnapshot().then(setWeatherSnapshot),
      api.weatherRaster().then(setWeatherRaster),
    ]).then((results) => {
      setDataStatus(results.some((result) => result.status === "rejected") ? "degraded" : "ready");
    });
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
      <AppHeader path={path} navigate={navigate} user={user} onUserChange={setUser} national={nationalRisk} weatherSnapshot={weatherSnapshot} />
      {path === "/" && <HomePage navigate={navigate} national={nationalRisk} weatherSnapshot={weatherSnapshot} dataStatus={dataStatus} />}
      {path === "/dashboard" && <DashboardPage navigate={navigate} national={nationalRisk} weatherSnapshot={weatherSnapshot} dataStatus={dataStatus} />}
      {path === "/saved" && <SavedPage navigate={navigate} weatherSnapshot={weatherSnapshot} dataStatus={dataStatus} />}
      {path === "/alerts" && <AlertsPage navigate={navigate} national={nationalRisk} dataStatus={dataStatus} />}
      {path.startsWith("/locations/") && <PlaceDetailPage navigate={navigate} slug={path.split("/").pop() ?? "miami"} />}
      {(path === "/map" || path === "/directions") && <MapPage navigate={navigate} national={nationalRisk} weatherSnapshot={weatherSnapshot} weatherRaster={weatherRaster} />}
      {!["/", "/dashboard", "/saved", "/alerts", "/map", "/directions"].includes(path) && !path.startsWith("/locations/") && (
        <NotFound navigate={navigate} />
      )}
      {toast && <div className="app-toast" role="status">{toast}</div>}
    </div>
  );
}

function AppHeader({ path, navigate, user, onUserChange, national, weatherSnapshot }: { path: string; navigate: Navigate; user: AuthUser | null; onUserChange: (user: AuthUser | null) => void; national: NationalRiskOverview | null; weatherSnapshot: NationalWeatherSnapshot | null }) {
  const activePath = path === "/directions" || path.startsWith("/locations/") ? "/map" : path;
  const initials = user?.email?.slice(0, 2).toUpperCase() ?? "IN";
  const atlanta = weatherSnapshot?.points.find((point) => point.city.toLowerCase().includes("atlanta") && point.data_status !== "UNAVAILABLE");
  return (
    <header className="app-header">
      <button className="wordmark" onClick={() => navigate("/")} aria-label="AtmosPath home">
        <span className="peach-mark"><i /><b /></span>
        <span><strong>AtmosPath</strong><small>Weather-aware navigation</small></span>
      </button>
      <nav className="primary-nav" aria-label="Primary navigation">
        {navItems.map(({ path: itemPath, label, icon: Icon }) => (
          <button key={itemPath} className={activePath === itemPath ? "active" : ""} onClick={() => navigate(itemPath)}>
            <Icon size={18} /><span>{label}</span>{label === "Alerts" && Boolean(national?.active_alerts) && <em>{national!.active_alerts > 99 ? "99+" : national!.active_alerts}</em>}
          </button>
        ))}
      </nav>
      <div className="header-tools">
        <button className="weather-chip" onClick={() => navigate("/locations/atlanta")}><CloudSun size={20} /><span><strong>{atlanta ? `${Math.round(atlanta.temperature_f)}°F` : "--"}</strong><small>Atlanta, GA</small></span></button>
        <button className="icon-button" aria-label="Notifications" onClick={() => navigate("/alerts")}><Bell size={19} /></button>
        <button className="avatar-button" title={user?.email ?? "Sign in"} onClick={() => {
          if (user) {
            logout();
            onUserChange(null);
          } else if (authConfigured()) {
            void login();
          } else {
            notify("Sign-in is available in the deployed preview.");
          }
        }}>{initials}</button>
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
