import { AlertTriangle, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import type { NationalRiskOverview } from "./types";
import { useAlertStream } from "./useAlertStream";

export function AlertLiveBanner() {
  const { isConnected, lastAlert } = useAlertStream();
  const [severeNotice, setSevereNotice] = useState<NationalRiskOverview | null>(null);
  const previousSevereCount = useRef<number | null>(null);

  useEffect(() => {
    if (!lastAlert) return;
    const previous = previousSevereCount.current;
    previousSevereCount.current = lastAlert.severe_alerts;
    if (previous !== null && lastAlert.severe_alerts > previous) setSevereNotice(lastAlert);
  }, [lastAlert]);

  if (!isConnected && !severeNotice) return null;

  return (
    <div className={`alert-live-banner${severeNotice ? " severe" : ""}`} role="status" aria-label="Live alert stream">
      {isConnected && (
        <span className="live-indicator" title="Alert stream connected">
          <i aria-hidden="true" />LIVE
        </span>
      )}
      {severeNotice && (
        <>
          <span className="alert-live-message">
            <AlertTriangle size={15} aria-hidden="true" />
            <span><strong>{severeNotice.severe_alerts} severe alerts active</strong> &mdash; {severeNotice.level.toLowerCase()} risk</span>
          </span>
          <button className="alert-live-dismiss" onClick={() => setSevereNotice(null)} aria-label="Dismiss severe alert notice">
            <X size={14} />
          </button>
        </>
      )}
    </div>
  );
}
