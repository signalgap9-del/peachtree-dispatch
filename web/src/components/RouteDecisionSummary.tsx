import { AlertTriangle, CheckCircle2, Clock3, ShieldCheck, TrendingDown } from "lucide-react";

import type { RouteDecision } from "../types";

export function RouteDecisionSummary({ decision, onSelectRecommended }: { decision: RouteDecision; onSelectRecommended: () => void }) {
  const Icon = decision.action === "delay_departure" ? AlertTriangle : ShieldCheck;
  return (
    <section className={`route-decision-summary ${decision.severity}`} aria-label="Recommended route decision">
      <div className="decision-title">
        <span><Icon size={16} /> Recommended route</span>
        <strong>{decision.recommendedLabel}</strong>
      </div>
      <h2>{decision.summary}</h2>
      <p>{decision.primaryReason}</p>
      <div className="decision-metrics">
        <span><TrendingDown size={14} /> Risk {decision.riskDelta > 0 ? `-${decision.riskDelta}` : "similar"}</span>
        <span><Clock3 size={14} /> {decision.timeDeltaMinutes > 0 ? `+${formatDuration(decision.timeDeltaMinutes)}` : "No delay"}</span>
        <span><CheckCircle2 size={14} /> {decision.confidence.toLowerCase()} confidence</span>
      </div>
      <button type="button" onClick={onSelectRecommended}>Apply recommended route</button>
    </section>
  );
}

function formatDuration(minutes: number) {
  const rounded = Math.round(minutes);
  const hours = Math.floor(rounded / 60);
  const mins = rounded % 60;
  if (!hours) return `${mins} min`;
  if (!mins) return `${hours} hr`;
  return `${hours} hr ${mins} min`;
}
