import type { DirectionsPlan, RouteAlternative, RouteDecision } from "./types";

export function deriveRouteDecision(plan: DirectionsPlan): RouteDecision | null {
  if (plan.decision) return {
    action: actionFromContract(plan.decision.action),
    recommendedAlternativeId: plan.decision.recommended_alternative_id,
    recommendedLabel: plan.decision.recommended_label,
    summary: plan.decision.summary,
    primaryReason: plan.decision.primary_reason,
    tradeoff: plan.decision.tradeoff,
    confidence: plan.decision.confidence,
    riskDelta: plan.decision.risk_delta,
    timeDeltaMinutes: plan.decision.time_delta_minutes,
    severity: plan.decision.severity.toLowerCase() as RouteDecision["severity"],
  };

  const alternatives = plan.alternatives?.length ? plan.alternatives : [planAsAlternative(plan)];
  if (!alternatives.length) return null;

  const fastest = alternatives.reduce((best, route) => route.duration_minutes < best.duration_minutes ? route : best);
  const lowestRisk = alternatives.reduce((best, route) => route.risk_score < best.risk_score ? route : best);
  const balanced = alternatives.find((route) => route.alternative_id.toLowerCase().includes("balanced") || route.label.toLowerCase().includes("balanced"));
  const allRisky = alternatives.every((route) => route.risk_score >= 55);
  const riskDelta = Math.max(0, fastest.risk_score - lowestRisk.risk_score);
  const timeDelta = Math.round(lowestRisk.duration_minutes - fastest.duration_minutes);
  const relativeDelayLimit = Math.max(30, Math.round(fastest.duration_minutes * 0.08));

  if (allRisky) {
    return buildDecision("delay_departure", lowestRisk, fastest, {
      summary: "Delay departure or take the least risky route",
      primaryReason: "Every route is currently elevated because severe weather is touching the corridor.",
      riskDelta,
      timeDelta,
    });
  }

  if (fastest.risk_score < 30) {
    return buildDecision("take_fastest", fastest, fastest, {
      summary: "Fastest route is safe enough right now",
      primaryReason: "The fastest option stays below the moderate-risk threshold with live weather coverage.",
      riskDelta: 0,
      timeDelta: 0,
    });
  }

  if (riskDelta >= 20 && timeDelta <= relativeDelayLimit) {
    return buildDecision("take_lower_risk", lowestRisk, fastest, {
      summary: "Lower weather risk is worth the extra time",
      primaryReason: `This route lowers the composite risk by ${riskDelta} points while staying within the acceptable delay window.`,
      riskDelta,
      timeDelta,
    });
  }

  if (balanced && fastest.risk_score - balanced.risk_score >= 10 && balanced.duration_minutes - fastest.duration_minutes <= Math.max(20, Math.round(fastest.duration_minutes * 0.04))) {
    return buildDecision("take_balanced", balanced, fastest, {
      summary: "Balanced route gives the best tradeoff",
      primaryReason: "The balanced option avoids a meaningful amount of weather risk without a large time penalty.",
      riskDelta: fastest.risk_score - balanced.risk_score,
      timeDelta: Math.round(balanced.duration_minutes - fastest.duration_minutes),
    });
  }

  return buildDecision("take_fastest", fastest, fastest, {
    summary: "Fastest route is the practical choice",
    primaryReason: "Risk reduction from the alternatives is not large enough to justify the added travel time.",
    riskDelta,
    timeDelta,
  });
}

function actionFromContract(action: NonNullable<DirectionsPlan["decision"]>["action"]): RouteDecision["action"] {
  if (action === "TAKE_LOWER_RISK") return "take_lower_risk";
  if (action === "TAKE_BALANCED") return "take_balanced";
  if (action === "DELAY_DEPARTURE") return "delay_departure";
  return "take_fastest";
}

function buildDecision(
  action: RouteDecision["action"],
  recommended: RouteAlternative,
  fastest: RouteAlternative,
  context: { summary: string; primaryReason: string; riskDelta: number; timeDelta: number },
): RouteDecision {
  const timeDelta = Math.round(recommended.duration_minutes - fastest.duration_minutes);
  const riskDelta = Math.max(0, fastest.risk_score - recommended.risk_score);
  return {
    action,
    recommendedAlternativeId: recommended.alternative_id,
    recommendedLabel: recommended.label,
    summary: context.summary,
    primaryReason: context.primaryReason,
    tradeoff: buildTradeoff(riskDelta, timeDelta),
    confidence: recommended.confidence ?? "MEDIUM",
    riskDelta: context.riskDelta,
    timeDeltaMinutes: context.timeDelta,
    severity: severityFromScore(recommended.risk_score),
  };
}

function buildTradeoff(riskDelta: number, timeDelta: number) {
  const timeText = timeDelta <= 0 ? "no additional time" : `+${formatDuration(timeDelta)}`;
  const riskText = riskDelta <= 0 ? "similar risk" : `-${riskDelta} risk`;
  return `${riskText} / ${timeText}`;
}

function planAsAlternative(plan: DirectionsPlan): RouteAlternative {
  return {
    alternative_id: "primary",
    label: "Primary route",
    coordinates: plan.coordinates,
    distance_miles: plan.distance_miles,
    duration_minutes: plan.duration_minutes,
    climate_delay_minutes: plan.climate_delay_minutes,
    risk_score: plan.risk_score,
    weather: plan.weather,
    hazards: [],
    model_version: plan.model_version,
    data_coverage: plan.weather.length ? plan.weather.filter((sample) => sample.data_status !== "UNAVAILABLE").length / plan.weather.length : 0,
  };
}

function severityFromScore(score: number): RouteDecision["severity"] {
  if (score >= 80) return "severe";
  if (score >= 55) return "high";
  if (score >= 30) return "moderate";
  return "low";
}

function formatDuration(minutes: number) {
  const rounded = Math.round(minutes);
  const hours = Math.floor(rounded / 60);
  const mins = rounded % 60;
  if (!hours) return `${mins} min`;
  if (!mins) return `${hours} hr`;
  return `${hours} hr ${mins} min`;
}
