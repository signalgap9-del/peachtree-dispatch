import { CloudOff, RefreshCw, WifiOff } from "lucide-react";

import { isSlowNetwork } from "./resilience";
import { useResilienceSnapshot } from "./useResilienceSnapshot";

export function NetworkStatusBanner() {
  const snapshot = useResilienceSnapshot();
  const slowNetwork = isSlowNetwork(snapshot);
  const showStale = snapshot.staleFallbackCount > 0 && snapshot.lastStaleKey;
  const showSlow = snapshot.online && slowNetwork;

  if (snapshot.online && !showStale && !showSlow) return null;

  const state = !snapshot.online
    ? {
      tone: "offline",
      icon: <WifiOff size={18} />,
      title: "Offline mode",
      detail: "AtmosPath keeps the map shell usable and shows safe cached public risk data when available. Private saved data is never cached for offline use.",
    }
    : showStale
      ? {
        tone: "stale",
        icon: <RefreshCw size={18} />,
        title: "Showing recently cached live data",
        detail: `One official source failed after retries, so this page is using ${snapshot.lastStaleKey} from ${formatShortTime(snapshot.lastStaleCachedAt)} while live requests recover.`,
      }
      : {
        tone: "slow",
        icon: <CloudOff size={18} />,
        title: "Slow network detected",
        detail: "Requests continue with conservative retries and short timeouts so route planning stays responsive on unstable connections.",
      };

  return (
    <div className={`network-resilience-banner ${state.tone}`} role="status" aria-label="Connection resilience">
      <span>{state.icon}</span>
      <div>
        <strong>{state.title}</strong>
        <small>{state.detail}</small>
      </div>
    </div>
  );
}

function formatShortTime(value?: string) {
  if (!value) return "the last successful session";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "the last successful session";
  return new Intl.DateTimeFormat("en-US", { hour: "numeric", minute: "2-digit" }).format(date);
}
