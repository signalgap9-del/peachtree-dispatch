import type { DirectionsPlan, HazardKind, RouteRiskSegment, WeatherRisk } from "./types";

export function deriveRouteRiskSegments(plan: DirectionsPlan): RouteRiskSegment[] {
  const liveSamples = plan.weather.filter((sample) => sample.data_status !== "UNAVAILABLE");
  const samples = liveSamples.length ? liveSamples : plan.weather;
  if (!samples.length) return [];

  const segmentCount = Math.min(5, Math.max(1, samples.length));
  const chunkSize = Math.ceil(samples.length / segmentCount);

  return Array.from({ length: segmentCount }, (_, index) => {
    const chunk = samples.slice(index * chunkSize, (index + 1) * chunkSize);
    const riskScore = Math.round(average(chunk.map((sample) => sample.risk_score)));
    const start = chunk[0];
    const end = chunk.at(-1) ?? start;
    const primaryHazard = primaryHazardFor(chunk);
    const coverage = chunk.length ? chunk.filter((sample) => sample.data_status !== "UNAVAILABLE").length / chunk.length : 0;
    return {
      id: `segment-${index + 1}`,
      label: start.id === end.id ? start.city : `${start.city} → ${end.city}`,
      riskScore,
      severity: severityFromScore(riskScore),
      primaryHazard,
      coverage,
      summary: segmentSummary(primaryHazard, riskScore),
    };
  }).filter((segment) => Number.isFinite(segment.riskScore));
}

function primaryHazardFor(samples: WeatherRisk[]): HazardKind {
  const totals = samples.reduce<Record<HazardKind, number>>((acc, sample) => {
    acc.rain += sample.precipitation_probability;
    acc.flood += sample.precipitation_probability >= 70 && sample.risk_score >= 55 ? sample.risk_score : 0;
    acc.wind += sample.wind_speed_mph >= 20 ? sample.wind_speed_mph * 3 : sample.wind_speed_mph;
    acc.heat += sample.temperature_f >= 90 ? (sample.temperature_f - 70) * 2 : 0;
    acc.alert += sample.risk_score >= 80 ? sample.risk_score : 0;
    return acc;
  }, { flood: 0, rain: 0, wind: 0, heat: 0, alert: 0, unknown: 0 });

  return (Object.entries(totals).sort(([, a], [, b]) => b - a)[0]?.[0] as HazardKind | undefined) ?? "unknown";
}

function segmentSummary(hazard: HazardKind, riskScore: number) {
  const level = severityFromScore(riskScore);
  const hazardText: Record<HazardKind, string> = {
    flood: "flood-sensitive rainfall",
    rain: "heavy precipitation",
    wind: "crosswinds",
    heat: "heat exposure",
    alert: "active severe alerts",
    unknown: "limited live coverage",
  };
  return `${level} risk from ${hazardText[hazard]}`;
}

function severityFromScore(score: number): RouteRiskSegment["severity"] {
  if (score >= 80) return "severe";
  if (score >= 55) return "high";
  if (score >= 30) return "moderate";
  return "low";
}

function average(values: number[]) {
  if (!values.length) return 0;
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}
