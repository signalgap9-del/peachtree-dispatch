import {
  Bell,
  Bookmark,
  ChevronDown,
  CloudSun,
  Gauge,
  Home,
  LayoutDashboard,
  LogIn,
  Map,
  ShieldAlert,
} from "lucide-react";
import { lazy, Suspense, useEffect, useState } from "react";
import { BrowserRouter, Route, Routes, useLocation, useNavigate, useParams } from "react-router-dom";
import "maplibre-gl/dist/maplibre-gl.css";

import { api } from "./api";
import { authConfigured, completeLogin, currentUser, googleAuthConfigured, login, loginWithGoogle, logout, type AuthUser } from "./auth";
import { useI18n } from "./i18n";
import { LanguageToggle } from "./LanguageToggle";
import { OperationalStatusPage } from "./OperationalStatusPage";
import { AlertsPage, DashboardPage, HomePage, PlaceDetailPage, PricingPage, SavedPage, UsagePage } from "./ProductPages";
import type { NationalRiskOverview, NationalWeatherSnapshot, WeatherRasterManifest } from "./types";
import { notify } from "./ui";
import "./styles.css";

export type Navigate = (path: string) => void;
export type DataStatus = "loading" | "ready" | "degraded";

const navItems = [
  { path: "/", labelKey: "nav.home", icon: Home },
  { path: "/map", labelKey: "nav.map", icon: Map },
  { path: "/dashboard", labelKey: "nav.dashboard", icon: LayoutDashboard },
  { path: "/saved", labelKey: "nav.saved", icon: Bookmark },
  { path: "/alerts", labelKey: "nav.alerts", icon: ShieldAlert },
  { path: "/usage", labelKey: "nav.usage", icon: Gauge },
] as const;

const MapPage = lazy(() => import("./MapPage").then((module) => ({ default: module.MapPage })));

function App() {
  return (
    <BrowserRouter>
      <AppShell />
    </BrowserRouter>
  );
}

function AppShell() {
  const { t } = useI18n();
  const location = useLocation();
  const routerNavigate = useNavigate();
  const path = location.pathname;
  const [nationalRisk, setNationalRisk] = useState<NationalRiskOverview | null>(null);
  const [weatherSnapshot, setWeatherSnapshot] = useState<NationalWeatherSnapshot | null>(null);
  const [weatherRaster, setWeatherRaster] = useState<WeatherRasterManifest | null>(null);
  const [toast, setToast] = useState("");
  const [user, setUser] = useState<AuthUser | null>(() => currentUser());
  const [dataStatus, setDataStatus] = useState<DataStatus>("loading");

  useEffect(() => {
    void completeLogin()
      .then((completed) => { if (completed) setUser(currentUser()); })
      .catch(() => notify(t("toast.loginFailed")));
  }, [t]);

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
    routerNavigate(nextPath);
    window.scrollTo({ top: 0, behavior: "smooth" });
  };

  return (
    <div className={`product-app ${path === "/map" || path === "/directions" ? "map-active" : ""}`}>
      <AppHeader path={path} navigate={navigate} user={user} onUserChange={setUser} national={nationalRisk} weatherSnapshot={weatherSnapshot} />
      <Routes>
        <Route path="/" element={<HomePage navigate={navigate} national={nationalRisk} weatherSnapshot={weatherSnapshot} weatherRaster={weatherRaster} dataStatus={dataStatus} />} />
        <Route path="/dashboard" element={<DashboardPage navigate={navigate} national={nationalRisk} weatherSnapshot={weatherSnapshot} weatherRaster={weatherRaster} dataStatus={dataStatus} />} />
        <Route path="/saved" element={<SavedPage navigate={navigate} weatherSnapshot={weatherSnapshot} dataStatus={dataStatus} />} />
        <Route path="/alerts" element={<AlertsPage navigate={navigate} national={nationalRisk} weatherSnapshot={weatherSnapshot} weatherRaster={weatherRaster} dataStatus={dataStatus} />} />
        <Route path="/usage" element={<UsagePage navigate={navigate} />} />
        <Route path="/status" element={<OperationalStatusPage navigate={navigate} dataStatus={dataStatus} national={nationalRisk} weatherSnapshot={weatherSnapshot} weatherRaster={weatherRaster} />} />
        <Route path="/pricing" element={<PricingPage navigate={navigate} />} />
        <Route path="/locations/:slug" element={<PlaceRoute navigate={navigate} weatherRaster={weatherRaster} />} />
        <Route path="/map" element={<MapRoute navigate={navigate} national={nationalRisk} weatherSnapshot={weatherSnapshot} weatherRaster={weatherRaster} />} />
        <Route path="/directions" element={<MapRoute navigate={navigate} national={nationalRisk} weatherSnapshot={weatherSnapshot} weatherRaster={weatherRaster} />} />
        <Route path="*" element={<NotFound navigate={navigate} />} />
      </Routes>
      {toast && <div className="app-toast" role="status">{toast}</div>}
    </div>
  );
}

function MapRoute({ navigate, national, weatherSnapshot, weatherRaster }: { navigate: Navigate; national: NationalRiskOverview | null; weatherSnapshot: NationalWeatherSnapshot | null; weatherRaster: WeatherRasterManifest | null }) {
  return (
    <Suspense fallback={<main className="map-loading-shell">Loading live map...</main>}>
      <MapPage navigate={navigate} national={national} weatherSnapshot={weatherSnapshot} weatherRaster={weatherRaster} />
    </Suspense>
  );
}

function PlaceRoute({ navigate, weatherRaster }: { navigate: Navigate; weatherRaster: WeatherRasterManifest | null }) {
  const params = useParams();
  return <PlaceDetailPage navigate={navigate} slug={params.slug ?? "miami"} weatherRaster={weatherRaster} />;
}

function AppHeader({ path, navigate, user, onUserChange, national, weatherSnapshot }: { path: string; navigate: Navigate; user: AuthUser | null; onUserChange: (user: AuthUser | null) => void; national: NationalRiskOverview | null; weatherSnapshot: NationalWeatherSnapshot | null }) {
  const { t } = useI18n();
  const activePath = path === "/directions" || path.startsWith("/locations/") ? "/map" : path;
  const initials = user?.email?.slice(0, 2).toUpperCase() ?? "IN";
  const atlanta = weatherSnapshot?.points.find((point) => point.city.toLowerCase().includes("atlanta") && point.data_status !== "UNAVAILABLE");
  return (
    <header className="app-header">
      <button className="wordmark" onClick={() => navigate("/")} aria-label={t("header.homeLabel")}>
        <span className="peach-mark"><i /><b /></span>
        <span><strong>AtmosPath</strong><small>{t("brand.tagline")}</small></span>
      </button>
      <nav className="primary-nav" aria-label="Primary navigation">
        {navItems.map(({ path: itemPath, labelKey, icon: Icon }) => {
          const label = t(labelKey);
          const isAlerts = labelKey === "nav.alerts";
          return (
            <button key={itemPath} className={activePath === itemPath ? "active" : ""} onClick={() => navigate(itemPath)}>
              <Icon size={18} /><span>{label}</span>{isAlerts && Boolean(national?.active_alerts) && <em>{national!.active_alerts > 99 ? "99+" : national!.active_alerts}</em>}
            </button>
          );
        })}
      </nav>
      <div className="header-tools">
        <LanguageToggle />
        {!user && <button className="google-auth-button" aria-label={t("header.googleSignUp")} onClick={() => {
          if (googleAuthConfigured()) {
            void loginWithGoogle();
          } else {
            notify(t("header.googleUnavailable"));
          }
        }}><LogIn size={15} /> {t("header.googleSignUp")}</button>}
        <button className="weather-chip" onClick={() => navigate("/locations/atlanta")}><CloudSun size={20} /><span><strong>{atlanta ? `${Math.round(atlanta.temperature_f)}°F` : "--"}</strong><small>Atlanta, GA</small></span></button>
        <button className="icon-button" aria-label={t("header.notifications")} onClick={() => navigate("/alerts")}><Bell size={19} /></button>
        <button className="avatar-button" title={user?.email ?? "Sign in"} onClick={() => {
          if (user) {
            logout();
            onUserChange(null);
          } else if (authConfigured()) {
            void login();
          } else {
            notify(t("header.authUnavailable"));
          }
        }}>{initials}</button>
        <ChevronDown size={15} />
      </div>
    </header>
  );
}

function NotFound({ navigate }: { navigate: Navigate }) {
  const { t } = useI18n();
  return (
    <main className="page-shell empty-page">
      <Map size={36} />
      <h1>{t("notFound.title")}</h1>
      <p>{t("notFound.detail")}</p>
      <div><button className="button secondary" onClick={() => navigate("/")}>{t("notFound.home")}</button><button className="button primary" onClick={() => navigate("/map")}>{t("notFound.map")}</button></div>
    </main>
  );
}

export default App;
