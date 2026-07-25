import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import App from "./App";
import { AppErrorBoundary } from "./components/AppErrorBoundary";
import { I18nProvider } from "./I18nProvider";
import { startPerformanceObservers } from "./performance";

hintApiPreconnect();
startPerformanceObservers();

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <AppErrorBoundary>
      <I18nProvider>
        <App />
      </I18nProvider>
    </AppErrorBoundary>
  </StrictMode>,
);

// The API origin is runtime configuration (VITE_API_URL), so the preconnect
// hint is injected here instead of hard-coded in index.html.
function hintApiPreconnect() {
  try {
    const origin = new URL(import.meta.env.VITE_API_URL ?? "http://localhost:8000", window.location.origin).origin;
    if (origin === window.location.origin) return;
    const link = document.createElement("link");
    link.rel = "preconnect";
    link.href = origin;
    document.head.appendChild(link);
  } catch {
    return;
  }
}

if (import.meta.env.PROD && import.meta.env.MODE !== "test" && "serviceWorker" in navigator) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("/sw.js").catch((error) => {
      console.warn("FreightScaler service worker registration failed", error);
    });
  });
}
