import maplibregl, { type Map, type MapGeoJSONFeature } from "maplibre-gl";
import { useEffect, useRef } from "react";

import type { MapLayerVisibility } from "./mapLayers";
import type { DirectionsPlan, HazardKind, NationalRiskOverview, NationalWeatherSnapshot, RiskAlert, RouteRiskSegment, WeatherRasterManifest } from "./types";

interface Props {
  plan: DirectionsPlan | null;
  risk: NationalRiskOverview | null;
  weatherSnapshot: NationalWeatherSnapshot | null;
  weatherRaster: WeatherRasterManifest | null;
  layers: MapLayerVisibility;
  segments: RouteRiskSegment[];
  recenterToken: number;
}

type MapHostElement = HTMLDivElement & { __atmospathMap?: Map };

interface AlertMarkerProperties {
  alert_id: string;
  event: string;
  severity: string;
  area: string;
  instruction?: string;
  effective: string;
  score: number;
}

interface SegmentDotProperties {
  label: string;
  hazard: string;
  riskScore: number;
}

const HAZARD_LABELS: Record<HazardKind, string> = {
  flood: "Flood risk",
  rain: "Heavy rain",
  wind: "Crosswinds",
  heat: "Heat exposure",
  winter: "Winter conditions",
  alert: "Active alerts",
  unknown: "Limited coverage",
};

// Alert marker severity bands: <40 advisory, 40-70 watch, >70 warning.
const ALERT_COLOR_EXPRESSION = [
  "case",
  [">", ["get", "score"], 70], "#d93025",
  [">=", ["get", "score"], 40], "#f97316",
  "#f9ab00",
];

// Segment dots reuse the app-wide route risk scale (low/moderate/high/severe).
const SEGMENT_COLOR_EXPRESSION = [
  "case",
  [">=", ["get", "riskScore"], 80], "#d93025",
  [">=", ["get", "riskScore"], 55], "#f97316",
  [">=", ["get", "riskScore"], 30], "#f9ab00",
  "#188038",
];

const LAYER_GROUPS: Record<keyof MapLayerVisibility, readonly string[]> = {
  heatmap: ["risk-heat"],
  alertZones: ["risk-alert-fill", "risk-alert-outline"],
  alertMarkers: ["alert-markers", "alert-markers-halo"],
  weatherRaster: ["weather-raster"],
  routeSegments: ["route-segment-dots", "route-segment-dots-halo"],
};

const productionStyle = {
  version: 8 as const,
  sources: {
    osm: {
      type: "raster" as const,
      tiles: ["https://basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}@2x.png"],
      tileSize: 256,
      attribution: "© OpenStreetMap contributors © CARTO",
    },
  },
  layers: [{ id: "osm", type: "raster" as const, source: "osm" }],
};

const testStyle = {
  version: 8 as const,
  sources: {},
  layers: [{
    id: "test-background",
    type: "background" as const,
    paint: { "background-color": "#eef3f8" },
  }],
};

const style = import.meta.env.MODE === "test" ? testStyle : productionStyle;

export function NetworkMap({ plan, risk, weatherSnapshot, weatherRaster, layers, segments, recenterToken }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const mapRef = useRef<Map | null>(null);
  const markers = useRef<maplibregl.Marker[]>([]);
  const pulseMarkers = useRef<maplibregl.Marker[]>([]);
  const layersRef = useRef(layers);
  const alertPopupRef = useRef<maplibregl.Popup | null>(null);
  const segmentPopupRef = useRef<maplibregl.Popup | null>(null);

  useEffect(() => {
    const host = container.current as MapHostElement | null;
    if (!host || mapRef.current) return;
    const map = new maplibregl.Map({
      container: host,
      style,
      center: [-98.58, 39.83],
      zoom: 3.4,
      pitch: 0,
      attributionControl: false,
    });
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "bottom-right");
    map.addControl(new maplibregl.AttributionControl({ compact: true }), "bottom-right");
    if (import.meta.env.MODE === "test") host.__atmospathMap = map;

    map.on("click", "alert-markers", (event) => {
      const properties = readAlertProperties(event.features?.[0]);
      if (!properties) return;
      if (!alertPopupRef.current) {
        alertPopupRef.current = new maplibregl.Popup({ closeOnClick: false, maxWidth: "280px", offset: 14, className: "atmospath-popup" });
      }
      alertPopupRef.current.setDOMContent(buildAlertPopupContent(properties)).setLngLat(event.lngLat).addTo(map);
    });
    map.on("mouseenter", "alert-markers", () => { map.getCanvas().style.cursor = "pointer"; });
    map.on("mouseleave", "alert-markers", () => { map.getCanvas().style.cursor = ""; });

    map.on("mousemove", "route-segment-dots", (event) => {
      const properties = readSegmentProperties(event.features?.[0]);
      if (!properties) return;
      if (!segmentPopupRef.current) {
        segmentPopupRef.current = new maplibregl.Popup({ closeButton: false, closeOnClick: false, offset: 10, className: "segment-popup" });
      }
      segmentPopupRef.current.setDOMContent(buildSegmentTooltip(properties)).setLngLat(event.lngLat).addTo(map);
      map.getCanvas().style.cursor = "pointer";
    });
    map.on("mouseleave", "route-segment-dots", () => {
      segmentPopupRef.current?.remove();
      map.getCanvas().style.cursor = "";
    });

    mapRef.current = map;
    return () => {
      alertPopupRef.current?.remove();
      segmentPopupRef.current?.remove();
      if (import.meta.env.MODE === "test") delete host.__atmospathMap;
      map.remove();
      mapRef.current = null;
    };
  }, []);

  // Toggles only flip layout visibility; layers are never rebuilt here.
  useEffect(() => {
    layersRef.current = layers;
    const map = mapRef.current;
    if (!map || !map.loaded()) return;
    applyLayerVisibility(map, layers, pulseMarkers.current);
  }, [layers]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const render = () => {
      markers.current.forEach((marker) => marker.remove());
      markers.current = [];
      for (const layer of ["route-segment-dots", "route-segment-dots-halo", "route-main", "route-casing"]) if (map.getLayer(layer)) map.removeLayer(layer);
      for (const source of ["route-segment-points", "route"]) if (map.getSource(source)) map.removeSource(source);
      if (!plan) return;
      map.addSource("route", {
        type: "geojson",
        data: { type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: plan.coordinates } },
      });
      map.addLayer({ id: "route-casing", type: "line", source: "route", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#ffffff", "line-width": 10, "line-opacity": 0.92 } });
      map.addLayer({ id: "route-main", type: "line", source: "route", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#1a73e8", "line-width": 6 } });
      const segmentFeatures = buildSegmentFeatures(plan.coordinates, segments);
      if (segmentFeatures.length) {
        map.addSource("route-segment-points", { type: "geojson", data: { type: "FeatureCollection", features: segmentFeatures } });
        map.addLayer({ id: "route-segment-dots-halo", type: "circle", source: "route-segment-points", paint: { "circle-radius": 6.5, "circle-color": "#ffffff", "circle-opacity": 0.92 } });
        map.addLayer({ id: "route-segment-dots", type: "circle", source: "route-segment-points", paint: { "circle-radius": 4.5, "circle-color": SEGMENT_COLOR_EXPRESSION as unknown as string } });
      }
      [plan.origin, plan.destination].forEach((place, index) => {
        const element = createPlaceMarker(index ? "destination" : "origin", index ? "B" : "A", place.display_name);
        markers.current.push(new maplibregl.Marker({ element, anchor: "bottom" }).setLngLat([place.longitude, place.latitude]).addTo(map));
      });
      plan.weather.forEach((weather) => {
        const element = createWeatherMarker(weather.risk_level.toLowerCase(), weather.risk_score, weather.city);
        markers.current.push(new maplibregl.Marker({ element }).setLngLat([weather.longitude, weather.latitude]).addTo(map));
      });
      if (plan.coordinates.length) {
        const first = plan.coordinates[0] as [number, number];
        const bounds = plan.coordinates.reduce((current, coordinate) => current.extend(coordinate as [number, number]), new maplibregl.LngLatBounds(first, first));
        map.fitBounds(bounds, { padding: { top: 110, right: 80, bottom: 80, left: 470 }, duration: 800 });
      }
      applyLayerVisibility(map, layersRef.current, pulseMarkers.current);
    };
    if (map.loaded()) render(); else map.once("load", render);
    return () => { map.off("load", render); };
  }, [plan, segments]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const render = () => {
      pulseMarkers.current.forEach((marker) => marker.remove());
      pulseMarkers.current = [];
      for (const layer of ["alert-markers", "alert-markers-halo", "risk-alert-outline", "risk-alert-fill", "risk-heat"]) {
        if (map.getLayer(layer)) map.removeLayer(layer);
      }
      for (const source of ["alert-marker-points", "risk-alerts", "risk-points"]) {
        if (map.getSource(source)) map.removeSource(source);
      }
      if (!risk && !weatherSnapshot) return;
      const alerts = risk?.alerts ?? [];
      const alertPoints = alerts
        .filter((alert) => alert.longitude != null && alert.latitude != null)
        .map((alert) => ({
          type: "Feature" as const,
          properties: { score: alert.score, event: alert.event, kind: "alert" },
          geometry: { type: "Point" as const, coordinates: [alert.longitude as number, alert.latitude as number] },
        }));
      const weatherPoints = (weatherSnapshot?.points ?? [])
        .filter((point) => point.data_status !== "UNAVAILABLE")
        .map((point) => ({
          type: "Feature" as const,
          properties: { score: point.risk_score, event: point.city, kind: "weather" },
          geometry: { type: "Point" as const, coordinates: [point.longitude, point.latitude] },
        }));
      const points = [...weatherPoints, ...alertPoints];
      const polygons = alerts
        .filter((alert) => alert.geometry)
        .map((alert) => ({ type: "Feature" as const, properties: { score: alert.score, event: alert.event }, geometry: alert.geometry! }));
      map.addSource("risk-points", { type: "geojson", data: { type: "FeatureCollection", features: points } });
      // Adapted from MapLibre's BSD-3-Clause create-a-heatmap-layer example.
      map.addLayer({
        id: "risk-heat", type: "heatmap", source: "risk-points", maxzoom: 9,
        paint: {
          "heatmap-weight": ["interpolate", ["linear"], ["get", "score"], 0, 0, 100, 1],
          "heatmap-intensity": ["interpolate", ["linear"], ["zoom"], 0, 1.4, 9, 3],
          "heatmap-color": ["interpolate", ["linear"], ["heatmap-density"], 0, "rgba(11,87,208,0)", 0.2, "#6ea8fe", 0.4, "#f9ab00", 0.7, "#f97316", 1, "#d93025"],
          "heatmap-radius": ["interpolate", ["linear"], ["zoom"], 0, 22, 9, 70],
          "heatmap-opacity": 0.72,
        },
      });
      map.addSource("risk-alerts", { type: "geojson", data: { type: "FeatureCollection", features: polygons } });
      map.addLayer({ id: "risk-alert-fill", type: "fill", source: "risk-alerts", paint: { "fill-color": ["interpolate", ["linear"], ["get", "score"], 20, "#f9ab00", 60, "#f97316", 90, "#d93025"], "fill-opacity": 0.18 } });
      map.addLayer({ id: "risk-alert-outline", type: "line", source: "risk-alerts", paint: { "line-color": "#b3261e", "line-width": 1.3, "line-opacity": 0.7 } });

      const effective = risk?.generated_at ?? weatherSnapshot?.generated_at ?? "";
      const markerAlerts = alerts.filter((alert): alert is RiskAlert & { longitude: number; latitude: number } => alert.longitude != null && alert.latitude != null);
      map.addSource("alert-marker-points", {
        type: "geojson",
        data: {
          type: "FeatureCollection",
          features: markerAlerts.map((alert) => ({
            type: "Feature" as const,
            properties: {
              alert_id: alert.alert_id,
              event: alert.event,
              severity: alert.severity,
              area: alert.area,
              instruction: alert.instruction ?? "",
              effective,
              score: alert.score,
            },
            geometry: { type: "Point" as const, coordinates: [alert.longitude, alert.latitude] },
          })),
        },
      });
      map.addLayer({
        id: "alert-markers-halo", type: "circle", source: "alert-marker-points",
        paint: {
          "circle-radius": ["interpolate", ["linear"], ["zoom"], 3, ["interpolate", ["linear"], ["get", "score"], 0, 5.5, 100, 12], 9, ["interpolate", ["linear"], ["get", "score"], 0, 8, 100, 24]],
          "circle-color": "#ffffff",
          "circle-opacity": 0.9,
        },
      });
      map.addLayer({
        id: "alert-markers", type: "circle", source: "alert-marker-points",
        paint: {
          "circle-radius": ["interpolate", ["linear"], ["zoom"], 3, ["interpolate", ["linear"], ["get", "score"], 0, 3.5, 100, 10], 9, ["interpolate", ["linear"], ["get", "score"], 0, 6, 100, 21]],
          "circle-color": ALERT_COLOR_EXPRESSION as unknown as string,
        },
      });
      markerAlerts
        .filter((alert) => alert.score > 70)
        .forEach((alert) => {
          const element = document.createElement("span");
          element.className = "alert-pulse-marker";
          element.setAttribute("aria-hidden", "true");
          pulseMarkers.current.push(new maplibregl.Marker({ element }).setLngLat([alert.longitude, alert.latitude]).addTo(map));
        });
      applyLayerVisibility(map, layersRef.current, pulseMarkers.current);
    };
    if (map.loaded()) render(); else map.once("load", render);
    return () => { map.off("load", render); };
  }, [risk, weatherSnapshot]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const render = () => {
      if (map.getLayer("weather-raster")) map.removeLayer("weather-raster");
      if (map.getSource("weather-raster")) map.removeSource("weather-raster");
      if (import.meta.env.MODE === "test" || weatherRaster?.source === "playwright") return;
      if (!weatherRaster?.url || weatherRaster.bounds.length < 2) return;
      const [[west, south], [east, north]] = weatherRaster.bounds;
      map.addSource("weather-raster", {
        type: "image",
        url: weatherRaster.url,
        coordinates: [[west, north], [east, north], [east, south], [west, south]],
      });
      const before = map.getLayer("risk-heat") ? "risk-heat" : undefined;
      map.addLayer({ id: "weather-raster", type: "raster", source: "weather-raster", paint: { "raster-opacity": 0.68 } }, before);
      applyLayerVisibility(map, layersRef.current, pulseMarkers.current);
    };
    if (map.loaded()) render(); else map.once("load", render);
    return () => { map.off("load", render); };
  }, [weatherRaster]);

  useEffect(() => {
    if (!mapRef.current || !recenterToken) return;
    mapRef.current.easeTo({ center: [-98.58, 39.83], zoom: 3.4, pitch: 0, bearing: 0, duration: 700 });
  }, [recenterToken]);

  return <div className="network-map" ref={container} />;
}

function applyLayerVisibility(map: Map, layers: MapLayerVisibility, pulseMarkerList: maplibregl.Marker[]) {
  (Object.keys(LAYER_GROUPS) as Array<keyof MapLayerVisibility>).forEach((key) => {
    const visibility = layers[key] ? "visible" : "none";
    for (const layerId of LAYER_GROUPS[key]) {
      if (map.getLayer(layerId)) map.setLayoutProperty(layerId, "visibility", visibility);
    }
  });
  for (const marker of pulseMarkerList) {
    marker.getElement().style.display = layers.alertMarkers ? "" : "none";
  }
}

function buildSegmentFeatures(coordinates: number[][], segments: RouteRiskSegment[]) {
  if (!segments.length || coordinates.length < 2) return [];
  const sampleCount = Math.min(28, Math.max(3, Math.floor(coordinates.length / 3)));
  return Array.from({ length: sampleCount }, (_, index) => {
    const position = Math.min(coordinates.length - 1, Math.round(((index + 1) / (sampleCount + 1)) * (coordinates.length - 1)));
    const coordinate = coordinates[position] as [number, number];
    const segment = segments[Math.min(segments.length - 1, Math.floor((index * segments.length) / sampleCount))];
    return {
      type: "Feature" as const,
      properties: { label: segment.label, hazard: HAZARD_LABELS[segment.primaryHazard], riskScore: segment.riskScore },
      geometry: { type: "Point" as const, coordinates: [coordinate[0], coordinate[1]] },
    };
  });
}

function readAlertProperties(feature: MapGeoJSONFeature | undefined): AlertMarkerProperties | null {
  const raw = feature?.properties;
  if (!raw || typeof raw.event !== "string" || typeof raw.score !== "number") return null;
  return {
    alert_id: typeof raw.alert_id === "string" ? raw.alert_id : "",
    event: raw.event,
    severity: typeof raw.severity === "string" ? raw.severity : "",
    area: typeof raw.area === "string" ? raw.area : "",
    instruction: typeof raw.instruction === "string" && raw.instruction ? raw.instruction : undefined,
    effective: typeof raw.effective === "string" ? raw.effective : "",
    score: raw.score,
  };
}

function readSegmentProperties(feature: MapGeoJSONFeature | undefined): SegmentDotProperties | null {
  const raw = feature?.properties;
  if (!raw || typeof raw.label !== "string" || typeof raw.riskScore !== "number") return null;
  return { label: raw.label, hazard: typeof raw.hazard === "string" ? raw.hazard : "Limited coverage", riskScore: raw.riskScore };
}

function buildAlertPopupContent(properties: AlertMarkerProperties) {
  const root = document.createElement("article");
  root.className = `alert-popup ${alertBand(properties.score)}`;

  const head = document.createElement("div");
  head.className = "alert-popup-head";
  const badge = document.createElement("span");
  badge.className = `alert-popup-badge ${alertBand(properties.score)}`;
  badge.textContent = properties.severity || bandLabel(properties.score);
  const score = document.createElement("span");
  score.className = "alert-popup-score";
  score.textContent = String(Math.round(properties.score));
  head.append(badge, score);

  const event = document.createElement("strong");
  event.className = "alert-popup-event";
  event.textContent = properties.event;

  root.append(head, event);

  if (properties.area) {
    const area = document.createElement("span");
    area.className = "alert-popup-area";
    area.textContent = properties.area;
    root.append(area);
  }
  const effective = formatEffectiveTime(properties.effective);
  if (effective) {
    const time = document.createElement("span");
    time.className = "alert-popup-time";
    time.textContent = effective;
    root.append(time);
  }
  if (properties.instruction) {
    const instruction = document.createElement("p");
    instruction.className = "alert-popup-instruction";
    instruction.textContent = snippet(properties.instruction);
    root.append(instruction);
  }
  return root;
}

function buildSegmentTooltip(properties: SegmentDotProperties) {
  const root = document.createElement("div");
  root.className = "segment-tooltip";
  const label = document.createElement("strong");
  label.textContent = properties.label;
  const detail = document.createElement("span");
  detail.textContent = `Risk ${Math.round(properties.riskScore)} · ${properties.hazard}`;
  root.append(label, detail);
  return root;
}

function alertBand(score: number): "advisory" | "watch" | "warning" {
  if (score > 70) return "warning";
  if (score >= 40) return "watch";
  return "advisory";
}

function bandLabel(score: number) {
  const band = alertBand(score);
  return band === "warning" ? "Warning" : band === "watch" ? "Watch" : "Advisory";
}

function formatEffectiveTime(iso: string) {
  const date = new Date(iso);
  if (!iso || Number.isNaN(date.getTime())) return "";
  return `Effective ${date.toLocaleString(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" })}`;
}

function snippet(text: string, limit = 140) {
  return text.length > limit ? `${text.slice(0, limit).trimEnd()}...` : text;
}

function createPlaceMarker(kind: "origin" | "destination", label: string, displayName: string) {
  const element = document.createElement("button");
  element.className = `place-marker ${kind === "destination" ? "destination" : "origin"}`;
  element.type = "button";
  element.setAttribute("aria-label", `${kind === "origin" ? "Origin" : "Destination"}: ${displayName}`);

  const labelNode = document.createElement("strong");
  labelNode.textContent = label;
  const nameNode = document.createElement("span");
  nameNode.textContent = displayName;
  element.append(labelNode, nameNode);
  return element;
}

function createWeatherMarker(level: string, score: number, city: string) {
  const element = document.createElement("button");
  element.className = `weather-bubble ${level}`;
  element.type = "button";
  element.setAttribute("aria-label", `${city} weather risk ${score}`);

  const scoreNode = document.createElement("strong");
  scoreNode.textContent = String(score);
  const cityNode = document.createElement("span");
  cityNode.textContent = city;
  element.append(scoreNode, cityNode);
  return element;
}
