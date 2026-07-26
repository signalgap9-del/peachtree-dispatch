import { AlertTriangle, ArrowRight, Settings2, ShieldCheck } from "lucide-react";

import type { DirectionsPlan } from "../types";

/**
 * Risk-aware smart default banner. When the fastest route's risk is below the
 * user's threshold this stays calm and green; when it crosses the threshold it
 * intervenes with an amber warning and a one-click safer route.
 */
export function RouteRecommendation({
  plan,
  onSelectSafer,
  onOpenSettings,
}: {
  plan: DirectionsPlan;
  onSelectSafer: () => void;
  onOpenSettings: () => void;
}) {
  if (!plan.recommended || !plan.recommendation_reason) return null;
  const lowRisk = plan.low_risk !== false;
  const tradeoff = lowRisk ? null : saferTradeoff(plan);
  return (
    <section
      className={`route-recommendation ${lowRisk ? "low-risk" : "high-risk"}`}
      aria-label="Route recommendation"
    >
      {lowRisk ? (
        <>
          <span className="recommendation-badge safe">
            <ShieldCheck size={14} /> Low risk · Clear route
          </span>
          <p>{plan.recommendation_reason}</p>
        </>
      ) : (
        <>
          <span className="recommendation-badge warning">
            <AlertTriangle size={14} /> High risk on fastest route
          </span>
          <p>{plan.recommendation_reason}</p>
          {tradeoff && (
            <span className="recommendation-tradeoff">
              +{tradeoff.minutes} min · risk {tradeoff.from} → {tradeoff.to}
            </span>
          )}
          <button type="button" onClick={onSelectSafer}>
            Take the safer route <ArrowRight size={14} />
          </button>
        </>
      )}
      <footer className="threshold-indicator">
        <span>
          Your risk threshold: <strong>{plan.risk_threshold ?? 40}</strong>
        </span>
        <button type="button" onClick={onOpenSettings} aria-label="Open risk threshold settings">
          <Settings2 size={12} /> Adjust
        </button>
      </footer>
    </section>
  );
}

function saferTradeoff(plan: DirectionsPlan): { minutes: number; from: number; to: number } | null {
  const alternatives = plan.alternatives;
  if (!alternatives || alternatives.length < 2) return null;
  const fastest = alternatives.reduce((best, route) =>
    route.duration_minutes < best.duration_minutes ? route : best,
  );
  const safest = alternatives.reduce((best, route) =>
    route.risk_score < best.risk_score ||
    (route.risk_score === best.risk_score && route.duration_minutes < best.duration_minutes)
      ? route
      : best,
  );
  if (safest.alternative_id === fastest.alternative_id) return null;
  return {
    minutes: Math.round(safest.duration_minutes - fastest.duration_minutes),
    from: fastest.risk_score,
    to: safest.risk_score,
  };
}
