import type { RouteRiskSegment } from "../types";
import { HazardSeverityIcon } from "./HazardSeverityIcon";

export function RouteSegmentRiskStrip({
  segments,
  selectedId,
  onSelect,
}: {
  segments: RouteRiskSegment[];
  selectedId: string | null;
  onSelect: (segment: RouteRiskSegment) => void;
}) {
  if (!segments.length) return null;
  return (
    <section className="route-segment-strip" aria-label="Risk by route segment">
      <div className="segment-strip-head">
        <strong>Risk by route segment</strong>
        <small>{segments.length} live segments</small>
      </div>
      <div className="segment-bars">
        {segments.map((segment) => (
          <button
            key={segment.id}
            type="button"
            className={`${segment.severity} ${selectedId === segment.id ? "selected" : ""}`}
            onClick={() => onSelect(segment)}
            aria-label={`${segment.label}: ${segment.summary}`}
          >
            <i style={{ height: `${Math.max(18, segment.riskScore)}%` }} />
          </button>
        ))}
      </div>
      <div className="segment-chip-row">
        {segments.map((segment) => (
          <button key={segment.id} type="button" className={selectedId === segment.id ? "selected" : ""} onClick={() => onSelect(segment)}>
            <HazardSeverityIcon hazard={segment.primaryHazard} severity={segment.severity} />
            <span><strong>{segment.label}</strong><small>{segment.summary} / {Math.round(segment.coverage * 100)}% coverage</small></span>
            <em>{segment.riskScore}</em>
          </button>
        ))}
      </div>
    </section>
  );
}
