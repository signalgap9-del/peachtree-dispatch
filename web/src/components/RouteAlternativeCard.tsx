import { CheckCircle2 } from "lucide-react";

import type { HazardExposure, HazardKind, RouteAlternative, RouteDecision } from "../types";
import { HazardSeverityIcon } from "./HazardSeverityIcon";

export function RouteAlternativeCard({
  alternative,
  fastest,
  decision,
  selected,
  onSelect,
}: {
  alternative: RouteAlternative;
  fastest: RouteAlternative;
  decision: RouteDecision | null;
  selected: boolean;
  onSelect: () => void;
}) {
  const riskDelta = fastest.risk_score - alternative.risk_score;
  const timeDelta = Math.round(alternative.duration_minutes - fastest.duration_minutes);
  const recommended = decision?.recommendedAlternativeId === alternative.alternative_id;
  const hazards = hazardKinds(alternative.hazards);
  return (
    <button type="button" className={`alternative-card ${selected ? "selected" : ""}`} onClick={onSelect}>
      <span className="alternative-top">
        <strong>{alternative.label}</strong>
        <em>{formatDuration(alternative.duration_minutes)}</em>
      </span>
      <span className="alternative-tradeoff">
        {recommended && <b><CheckCircle2 size={13} /> Recommended</b>}
        <i className={riskClassFromScore(alternative.risk_score)}>Risk {alternative.risk_score}</i>
        <small>{riskDelta > 0 ? `-${riskDelta} risk` : riskDelta < 0 ? `+${Math.abs(riskDelta)} risk` : "same risk"}</small>
        <small>{timeDelta > 0 ? `+${formatDuration(timeDelta)}` : timeDelta < 0 ? `${formatDuration(Math.abs(timeDelta))} faster` : "same time"}</small>
      </span>
      <span className="alternative-meta">
        <b>{Math.round(alternative.distance_miles).toLocaleString()} mi</b>
        <small>{Math.round((alternative.data_coverage ?? 0) * 100)}% live coverage</small>
        <small>{(alternative.confidence ?? "MEDIUM").toLowerCase()} confidence</small>
      </span>
      <span className="hazard-icon-row">
        {hazards.map((hazard) => <HazardSeverityIcon key={hazard} hazard={hazard} severity={riskClassFromScore(alternative.risk_score)} />)}
        <span>{alternative.hazards.length ? alternative.hazards.map((hazard) => hazard.category.replaceAll("_", " ").toLowerCase()).join(" / ") : "No active route hazard alerts"}</span>
      </span>
    </button>
  );
}

function hazardKinds(hazards: HazardExposure[]): HazardKind[] {
  const kinds = hazards.map((hazard) => {
    const category = hazard.category.toLowerCase();
    if (category.includes("flood")) return "flood";
    if (category.includes("rain") || category.includes("precip")) return "rain";
    if (category.includes("wind")) return "wind";
    if (category.includes("heat")) return "heat";
    if (category.includes("ice") || category.includes("snow") || category.includes("winter") || category.includes("freez")) return "winter";
    if (category.includes("alert") || category.includes("storm")) return "alert";
    return "unknown";
  });
  return Array.from(new Set(kinds.length ? kinds : ["unknown"]));
}

function formatDuration(minutes: number) {
  const rounded = Math.round(minutes);
  const hours = Math.floor(rounded / 60);
  const mins = rounded % 60;
  if (!hours) return `${mins} min`;
  if (!mins) return `${hours} hr`;
  return `${hours} hr ${mins} min`;
}

function riskClassFromScore(score: number) {
  if (score >= 80) return "severe";
  if (score >= 55) return "high";
  if (score >= 30) return "moderate";
  return "low";
}
