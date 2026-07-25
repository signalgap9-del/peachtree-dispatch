import { ChevronDown, CloudRain, Flame, Layers3, MapPin, Milestone, ShieldAlert, type LucideIcon } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import { visibleLayerCount, type MapLayerVisibility } from "./mapLayers";

interface LayerOption {
  key: keyof MapLayerVisibility;
  label: string;
  icon: LucideIcon;
}

const LAYER_OPTIONS: LayerOption[] = [
  { key: "heatmap", label: "Risk heatmap", icon: Flame },
  { key: "alertZones", label: "Alert zones", icon: ShieldAlert },
  { key: "alertMarkers", label: "Alert markers", icon: MapPin },
  { key: "weatherRaster", label: "Weather raster", icon: CloudRain },
  { key: "routeSegments", label: "Route segments", icon: Milestone },
];

export function MapLayerControl({ layers, onChange }: { layers: MapLayerVisibility; onChange: (next: MapLayerVisibility) => void }) {
  const [open, setOpen] = useState(() => window.matchMedia("(min-width: 761px)").matches);
  const toggleRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      setOpen(false);
      toggleRef.current?.focus();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [open]);

  const activeCount = visibleLayerCount(layers);

  return (
    <div className="map-layer-control">
      <button
        ref={toggleRef}
        type="button"
        className={`map-layer-toggle${open ? " open" : ""}`}
        aria-expanded={open}
        aria-label="Toggle map layers"
        onClick={() => setOpen((value) => !value)}
      >
        <Layers3 size={19} />
        <ChevronDown size={14} aria-hidden="true" />
      </button>
      {open && (
        <div className="map-layer-panel" role="group" aria-label="Map layers">
          <span className="map-layer-panel-head">
            <strong>Map layers</strong>
            <small>{activeCount} of {LAYER_OPTIONS.length} visible</small>
          </span>
          {LAYER_OPTIONS.map((option) => (
            <button
              key={option.key}
              type="button"
              role="switch"
              aria-checked={layers[option.key]}
              className={`layer-switch${layers[option.key] ? " on" : ""}`}
              onClick={() => onChange({ ...layers, [option.key]: !layers[option.key] })}
            >
              <option.icon size={16} aria-hidden="true" />
              <span>{option.label}</span>
              <i className="layer-switch-track" aria-hidden="true"><b /></i>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
