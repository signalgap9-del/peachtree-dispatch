import {
  ArrowLeft,
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
  Settings,
} from "lucide-react";
import { lazy, Suspense, useCallback, useEffect, useState } from "react";
import { BrowserRouter, Navigate as RouterNavigate, Route, Routes, useLocation, useNavigate, useParams } from "react-router-dom";
import "maplibre-gl/dist/maplibre-gl.css";

import { api } from "./api";
import { AlertLiveBanner } from "./AlertLiveBanner";
import { connectAlertStream, disconnectAlertStream, subscribeAlertUpdates, subscribeRiskSuggestions } from "./alertStream";
import { authConfigured, completeLogin, currentUser, googleAuthConfigured, login, loginWithGoogle, logout, type AuthUser } from "./auth";
import type { ChatDraft } from "./ChatPanel";
import { ChatToggleButton } from "./ChatToggleButton";
import { RouteErrorBoundary } from "./components/RouteErrorBoundary";
import { useI18n } from "./i18n";
import { NetworkStatusBanner } from "./NetworkStatusBanner";
import { Onboarding, shouldShowOnboarding } from "./Onboarding";
import { ProactiveSuggestionBanner } from "./ProactiveSuggestionBanner";
import { ToastHost } from "./Toast";
import type { NationalRiskOverview, NationalWeatherSnapshot, RiskSuggestion, WeatherRasterManifest } from "./types";
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
// Route-level code splitting: each page cluster loads on navigation, keeping
// the initial bundle to the app shell, header, and shared live-data state.
const ChatPanel = lazy(() => import("./ChatPanel").then((module) => ({ default: module.ChatPanel })));
const OperationalStatusPage = lazy(() => import("./OperationalStatusPage").then((module) => ({ default: module.OperationalStatusPage })));
// Marketing landing page owns "/"; the product app lives under /app/*.
const LandingPage = lazy(() => import("./LandingPage"));
const HomePage = lazy(() => import("./ProductPages").then((module) => ({ default: module.HomePage })));
const DashboardPage = lazy(() => import("./ProductPages").then((module) => ({ default: module.DashboardPage })));
const SavedPage = lazy(() => import("./ProductPages").then((module) => ({ default: module.SavedPage })));
const AlertsPage = lazy(() => import("./ProductPages").then((module) => ({ default: module.AlertsPage })));
const UsagePage = lazy(() => import("./ProductPages").then((module) => ({ default: module.UsagePage })));
const PricingPage = lazy(() => import("./ProductPages").then((module) => ({ default: module.PricingPage })));
const PlaceDetailPage = lazy(() => import("./ProductPages").then((module) => ({ default: module.PlaceDetailPage })));
const SettingsPage = lazy(() => import("./SettingsPage").then((module) => ({ default: module.SettingsPage })));
const TermsPage = lazy(() => import("./LegalPages").then((module) => ({ default: module.TermsPage })));
const PrivacyPage = lazy(() => import("./LegalPages").then((module) => ({ default: module.PrivacyPage })));

function App() {
  return (
    <BrowserRouter>
      <SiteRoutes />
    </BrowserRouter>
  );
}

function SiteRoutes() {
  const location = useLocation();
  const { pathname, search } = location;
  if (pathname === "/app" || pathname.startsWith("/app/")) {
    return <AppShell />;
  }
  if (pathname === "/") {
    const params = new URLSearchParams(search);
    if (params.has("code") || params.has("state")) {
      // OAuth callback: complete sign-in inside the app shell.
      return <RouterNavigate to={{ pathname: "/app", search }} replace />;
    }
    return (
      <Suspense fallback={null}>
        <LandingPage />
      </Suspense>
    );
  }
  if (pathname === "/welcome") {
    // The landing page moved to the root.
    return <RouterNavigate to={{ pathname: "/", search }} replace />;
  }
  // Legacy deep links (bookmarks, tests) keep working under /app.
  return <RouterNavigate to={{ pathname: `/app${pathname}`, search }} replace />;
}

function AppShell() {
  const { t } = useI18n();
  const location = useLocation();
  const routerNavigate = useNavigate();
  const path = location.pathname.replace(/^\/app/, "") || "/";
  const [nationalRisk, setNationalRisk] = useState<NationalRiskOverview | null>(null);
  const [weatherSnapshot, setWeatherSnapshot] = useState<NationalWeatherSnapshot | null>(null);
  const [weatherRaster, setWeatherRaster] = useState<WeatherRasterManifest | null>(null);
  const [user, setUser] = useState<AuthUser | null>(() => currentUser());
  const [dataStatus, setDataStatus] = useState<DataStatus>("loading");
  const [chatOpen, setChatOpen] = useState(false);
  const [chatUnread, setChatUnread] = useState(0);
  const [chatDraft, setChatDraft] = useState<ChatDraft | null>(null);
  const [suggestion, setSuggestion] = useState<RiskSuggestion | null>(null);
  const [onboardingOpen, setOnboardingOpen] = useState(shouldShowOnboarding);

  useEffect(() => {
    void completeLogin()
      .then((completed) => { if (completed) setUser(currentUser()); })
      .catch(() => notify(t("toast.loginFailed")));
  }, [t]);

  const loadLiveData = useCallback(() => {
    setDataStatus("loading");
    void Promise.allSettled([
      api.nationalRisk().then(setNationalRisk),
      api.weatherSnapshot().then(setWeatherSnapshot),
      api.weatherRaster().then(setWeatherRaster),
    ]).then((results) => {
      setDataStatus(results.some((result) => result.status === "rejected") ? "degraded" : "ready");
    });
  }, []);

  useEffect(() => { loadLiveData(); }, [loadLiveData]);

  // DataNotice retry buttons broadcast this event instead of threading a
  // reload callback through every page.
  useEffect(() => {
    const onReload = () => loadLiveData();
    window.addEventListener("freightscaler:reload-data", onReload);
    return () => window.removeEventListener("freightscaler:reload-data", onReload);
  }, [loadLiveData]);

  useEffect(() => {
    connectAlertStream();
    const unsubscribeAlerts = subscribeAlertUpdates(setNationalRisk);
    return () => {
      unsubscribeAlerts();
      disconnectAlertStream();
    };
  }, []);

  // Global shortcuts: "/" focuses the visible search field, Esc closes the assistant.
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "/") {
        const target = event.target as HTMLElement | null;
        const typing = target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement || Boolean(target?.isContentEditable);
        if (typing) return;
        const search = document.querySelector<HTMLInputElement>("input[data-shortcut-search]");
        if (search) {
          event.preventDefault();
          search.focus();
          search.select();
        }
      } else if (event.key === "Escape") {
        setChatOpen(false);
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  // Proactive attention: risk suggestions pushed on the alert stream badge
  // the assistant toggle while the panel is closed.
  useEffect(() => {
    if (chatOpen) return;
    return subscribeRiskSuggestions(() => setChatUnread((count) => count + 1));
  }, [chatOpen]);

  // The latest suggestion also drives the amber banner, independent of chat state.
  useEffect(() => subscribeRiskSuggestions(setSuggestion), []);

  const openChat = () => {
    setChatOpen(true);
    setChatUnread(0);
  };

  const openChatWithDraft = useCallback((text: string) => {
    setChatDraft({ text, nonce: Date.now() });
    setChatOpen(true);
    setChatUnread(0);
  }, []);

  const dismissSuggestionBanner = useCallback(() => setSuggestion(null), []);

  const handleSuggestionSwitch = useCallback(() => {
    setSuggestion(null);
    openChatWithDraft("Switch to the lower-risk route");
  }, [openChatWithDraft]);

  const navigate: Navigate = (nextPath) => {
    const target = nextPath === "/" ? "/app" : nextPath.startsWith("/app") ? nextPath : `/app${nextPath}`;
    routerNavigate(target);
    const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    window.scrollTo({ top: 0, behavior: reduceMotion ? "auto" : "smooth" });
  };

  const backToSite = useCallback(() => routerNavigate("/"), [routerNavigate]);

  const isMapPage = path === "/map" || path === "/directions";

  return (
    <div className={`product-app ${isMapPage ? "map-active" : ""}`}>
      <a className="skip-link" href="#main-content">{t("a11y.skip")}</a>
      <AppHeader path={path} navigate={navigate} onBackToSite={backToSite} user={user} onUserChange={setUser} national={nationalRisk} weatherSnapshot={weatherSnapshot} />
      <NetworkStatusBanner />
      <ProactiveSuggestionBanner suggestion={suggestion} onSwitch={handleSuggestionSwitch} onDismissed={dismissSuggestionBanner} />
      <AlertLiveBanner />
      <div id="main-content" tabIndex={-1} className="app-main">
        <RouteErrorBoundary scope="pages">
          <Suspense fallback={<PageFallback />}>
            <Routes>
              <Route path="/app" element={<HomePage navigate={navigate} national={nationalRisk} weatherSnapshot={weatherSnapshot} weatherRaster={weatherRaster} dataStatus={dataStatus} />} />
              <Route path="/app/dashboard" element={<DashboardPage navigate={navigate} national={nationalRisk} weatherSnapshot={weatherSnapshot} weatherRaster={weatherRaster} dataStatus={dataStatus} />} />
              <Route path="/app/saved" element={<SavedPage navigate={navigate} weatherSnapshot={weatherSnapshot} dataStatus={dataStatus} />} />
              <Route path="/app/alerts" element={<AlertsPage navigate={navigate} national={nationalRisk} weatherSnapshot={weatherSnapshot} weatherRaster={weatherRaster} dataStatus={dataStatus} />} />
              <Route path="/app/usage" element={<UsagePage navigate={navigate} />} />
              <Route path="/app/status" element={<OperationalStatusPage navigate={navigate} dataStatus={dataStatus} national={nationalRisk} weatherSnapshot={weatherSnapshot} weatherRaster={weatherRaster} />} />
              <Route path="/app/pricing" element={<PricingPage navigate={navigate} />} />
              <Route path="/app/settings" element={<SettingsPage navigate={navigate} />} />
              <Route path="/app/legal/terms" element={<TermsPage navigate={navigate} />} />
              <Route path="/app/legal/privacy" element={<PrivacyPage navigate={navigate} />} />
              <Route path="/app/locations/:slug" element={<PlaceRoute navigate={navigate} weatherRaster={weatherRaster} />} />
              <Route path="/app/map" element={<MapRoute navigate={navigate} national={nationalRisk} weatherSnapshot={weatherSnapshot} weatherRaster={weatherRaster} />} />
              <Route path="/app/directions" element={<MapRoute navigate={navigate} national={nationalRisk} weatherSnapshot={weatherSnapshot} weatherRaster={weatherRaster} />} />
              <Route path="*" element={<NotFound navigate={navigate} />} />
            </Routes>
          </Suspense>
        </RouteErrorBoundary>
      </div>
      {!isMapPage && <AppFooter navigate={navigate} />}
      {onboardingOpen && path === "/" && <Onboarding onDone={() => setOnboardingOpen(false)} />}
      <ToastHost />
      <ChatToggleButton
        open={chatOpen}
        unread={chatUnread}
        attention={chatUnread > 0}
        onClick={() => (chatOpen ? setChatOpen(false) : openChat())}
      />
      <RouteErrorBoundary scope="chat">
        <Suspense fallback={null}>
          <ChatPanel open={chatOpen} onClose={() => setChatOpen(false)} draft={chatDraft} />
        </Suspense>
      </RouteErrorBoundary>
    </div>
  );
}

function MapRoute({ navigate, national, weatherSnapshot, weatherRaster }: { navigate: Navigate; national: NationalRiskOverview | null; weatherSnapshot: NationalWeatherSnapshot | null; weatherRaster: WeatherRasterManifest | null }) {
  return (
    <RouteErrorBoundary scope="map">
      <Suspense fallback={<main className="map-loading-shell">Loading live map...</main>}>
        <MapPage navigate={navigate} national={national} weatherSnapshot={weatherSnapshot} weatherRaster={weatherRaster} />
      </Suspense>
    </RouteErrorBoundary>
  );
}

function PageFallback() {
  const { t } = useI18n();
  return (
    <main className="page-shell page-suspense">
      <span className="page-suspense-spinner" aria-hidden="true" />
      <p role="status">{t("page.loading")}</p>
    </main>
  );
}

function PlaceRoute({ navigate, weatherRaster }: { navigate: Navigate; weatherRaster: WeatherRasterManifest | null }) {
  const params = useParams();
  return <PlaceDetailPage navigate={navigate} slug={params.slug ?? "miami"} weatherRaster={weatherRaster} />;
}

function AppHeader({ path, navigate, onBackToSite, user, onUserChange, national, weatherSnapshot }: { path: string; navigate: Navigate; onBackToSite: () => void; user: AuthUser | null; onUserChange: (user: AuthUser | null) => void; national: NationalRiskOverview | null; weatherSnapshot: NationalWeatherSnapshot | null }) {
  const { t } = useI18n();
  const activePath = path === "/directions" || path.startsWith("/locations/") ? "/map" : path;
  const initials = user?.email?.slice(0, 2).toUpperCase() ?? "IN";
  const atlanta = weatherSnapshot?.points.find((point) => point.city.toLowerCase().includes("atlanta") && point.data_status !== "UNAVAILABLE");
  return (
    <header className="app-header">
      <button className="wordmark" onClick={() => navigate("/")} aria-label={t("header.homeLabel")}>
        <span className="peach-mark"><i /><b /></span>
        <span><strong>FreightScaler</strong><small>{t("brand.tagline")}</small></span>
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
        <button className="back-to-site" onClick={onBackToSite}><ArrowLeft size={14} /><span>{t("app.backToSite")}</span></button>
        {!user && <button className="google-auth-button" aria-label={t("header.googleSignUp")} onClick={() => {
          if (googleAuthConfigured()) {
            void loginWithGoogle();
          } else {
            notify(t("header.googleUnavailable"));
          }
        }}><LogIn size={15} /> {t("header.googleSignUp")}</button>}
        <button className="weather-chip" onClick={() => navigate("/locations/atlanta")}><CloudSun size={20} /><span><strong>{atlanta ? `${Math.round(atlanta.temperature_f)}°F` : "--"}</strong><small>Atlanta, GA</small></span></button>
        <button className="icon-button" aria-label={t("footer.settings")} onClick={() => navigate("/settings")}><Settings size={19} /></button>
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

function AppFooter({ navigate }: { navigate: Navigate }) {
  const { t } = useI18n();
  return (
    <footer className="app-footer">
      <span>© 2026 FreightScaler · {t("brand.tagline")}</span>
      <nav aria-label="Footer">
        <button onClick={() => navigate("/legal/terms")}>{t("footer.terms")}</button>
        <button onClick={() => navigate("/legal/privacy")}>{t("footer.privacy")}</button>
        <button onClick={() => navigate("/status")}>{t("footer.status")}</button>
        <button onClick={() => navigate("/settings")}>{t("footer.settings")}</button>
      </nav>
    </footer>
  );
}

function NotFound({ navigate }: { navigate: Navigate }) {
  const { t } = useI18n();
  return (
    <main className="page-shell empty-page not-found-page">
      <p className="not-found-code" aria-hidden="true">404</p>
      <Map size={36} />
      <h1>{t("notFound.title")}</h1>
      <p>{t("notFound.detail")}</p>
      <div><button className="button secondary" onClick={() => navigate("/")}>{t("notFound.home")}</button><button className="button primary" onClick={() => navigate("/map")}>{t("notFound.map")}</button></div>
    </main>
  );
}

export default App;
