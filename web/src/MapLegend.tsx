import { ChevronDown, List } from "lucide-react";
import { useState } from "react";

export function MapLegend() {
  const [open, setOpen] = useState(() => window.matchMedia("(min-width: 761px)").matches);

  return (
    <div className={`map-legend${open ? " open" : ""}`}>
      <button
        type="button"
        className="map-legend-toggle"
        aria-expanded={open}
        aria-label={open ? "Hide map legend" : "Show map legend"}
        onClick={() => setOpen((value) => !value)}
      >
        <List size={15} aria-hidden="true" />
        <span>Legend</span>
        <ChevronDown size={14} aria-hidden="true" />
      </button>
      {open && (
        <div className="map-legend-body">
          <strong className="map-legend-title">Risk score</strong>
          <div className="legend-gradient" role="img" aria-label="Risk color scale from 0 (low, green) to 100 (severe, red)" />
          <div className="legend-scale" aria-hidden="true"><span>0</span><span>40</span><span>70</span><span>100</span></div>
          <div className="legend-bands">
            <span><i className="legend-dot advisory" />Advisory</span>
            <span><i className="legend-dot watch" />Watch</span>
            <span><i className="legend-dot warning" />Warning</span>
          </div>
          <strong className="map-legend-title">Marker size = severity</strong>
          <div className="legend-sizes" aria-label="Larger alert markers mean higher risk scores">
            <span><i className="legend-size sm" />Minor</span>
            <span><i className="legend-size md" />Elevated</span>
            <span><i className="legend-size lg" />Severe</span>
          </div>
          <strong className="map-legend-title">Layers</strong>
          <div className="legend-layers">
            <span><i className="legend-swatch heat" />Risk heatmap</span>
            <span><i className="legend-swatch zone" />Alert zone</span>
            <span><i className="legend-swatch route" />Planned route</span>
            <span><i className="legend-swatch segment" />Segment risk</span>
          </div>
        </div>
      )}
    </div>
  );
}
