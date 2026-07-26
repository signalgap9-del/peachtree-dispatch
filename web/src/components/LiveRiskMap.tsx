import maplibregl, { type Map } from "maplibre-gl";
import { useEffect, useMemo, useRef } from "react";

import { mapStyle } from "../mapStyle";
import type { LocationRisk, NationalRiskOverview, NationalWeatherSnapshot, RiskAlert, WeatherRasterManifest, WeatherRisk } from "../types";

type Props = {
  national?: NationalRiskOverview | null;
  weatherSnapshot?: NationalWeatherSnapshot | null;
  weatherRaster?: WeatherRasterManifest | null;
  locationRisk?: LocationRisk | null;
  compact?: boolean;
  regional?: boolean;
};

export function LiveRiskMap({ national, weatherSnapshot, weatherRaster, locationRisk, compact, regional }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const mapRef = useRef<Map | null>(null);
  const markers = useRef<maplibregl.Marker[]>([]);
  const weatherPoints = useMemo(
    () => locationRisk ? [locationRisk.weather] : topWeatherPoints(weatherSnapshot ?? null, compact ? 5 : 12),
    [compact, locationRisk, weatherSnapshot],
  );
  const alertPoints = useMemo(() => locationRisk ? locationRisk.alerts : national?.alerts ?? [], [locationRisk, national]);
  const hasLiveData = weatherPoints.length > 0 || alertPoints.some((alert) => hasCoordinates(alert) || Boolean(alert.geometry));

  useEffect(() => {
    if (!container.current || mapRef.current) return;
    const center = locationRisk ? [locationRisk.place.longitude, locationRisk.place.latitude] as [number, number] : [-98.58, 39.83] as [number, number];
    const map = new maplibregl.Map({
      container: container.current,
      style: mapStyle,
      center,
      zoom: locationRisk || regional ? 6 : compact ? 3 : 3.3,
      attributionControl: false,
      interactive: false,
    });
    map.addControl(new maplibregl.AttributionControl({ compact: true }), "bottom-right");
    mapRef.current = map;
    return () => {
      markers.current.forEach((marker) => marker.remove());
      markers.current = [];
      map.remove();
      mapRef.current = null;
    };
  }, [compact, locationRisk, regional]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    syncMarkers(map, markers.current, weatherPoints, alertPoints);
    const render = () => {
      for (const layer of ["mini-risk-alert-outline", "mini-risk-alert-fill", "mini-risk-heat"]) {
        if (map.getLayer(layer)) map.removeLayer(layer);
      }
      for (const source of ["mini-risk-alerts", "mini-risk-points"]) {
        if (map.getSource(source)) map.removeSource(source);
      }

      const weatherFeatures = weatherPoints.map((point) => ({
        type: "Feature" as const,
        properties: { score: point.risk_score, label: point.city },
        geometry: { type: "Point" as const, coordinates: [point.longitude, point.latitude] },
      }));
      const alertFeatures = alertPoints
        .filter((alert) => alert.longitude != null && alert.latitude != null)
        .map((alert) => ({
          type: "Feature" as const,
          properties: { score: alert.score, label: alert.event },
          geometry: { type: "Point" as const, coordinates: [alert.longitude as number, alert.latitude as number] },
        }));
      const polygonFeatures = alertPoints
        .filter((alert) => alert.geometry)
        .map((alert) => ({ type: "Feature" as const, properties: { score: alert.score, event: alert.event }, geometry: alert.geometry! }));

      addRasterLayer(map, weatherRaster, "mini-risk-heat");
      map.addSource("mini-risk-points", { type: "geojson", data: { type: "FeatureCollection", features: [...weatherFeatures, ...alertFeatures] } });
      map.addLayer({
        id: "mini-risk-heat",
        type: "heatmap",
        source: "mini-risk-points",
        paint: {
          "heatmap-weight": ["interpolate", ["linear"], ["get", "score"], 0, 0, 100, 1],
          "heatmap-intensity": compact ? 1.2 : 1.8,
          "heatmap-color": ["interpolate", ["linear"], ["heatmap-density"], 0, "rgba(11,87,208,0)", 0.25, "#6ea8fe", 0.48, "#f9ab00", 0.72, "#f97316", 1, "#d93025"],
          "heatmap-radius": compact ? 32 : 56,
          "heatmap-opacity": 0.74,
        },
      });
      map.addSource("mini-risk-alerts", { type: "geojson", data: { type: "FeatureCollection", features: polygonFeatures } });
      map.addLayer({ id: "mini-risk-alert-fill", type: "fill", source: "mini-risk-alerts", paint: { "fill-color": ["interpolate", ["linear"], ["get", "score"], 20, "#f9ab00", 60, "#f97316", 90, "#d93025"], "fill-opacity": 0.2 } });
      map.addLayer({ id: "mini-risk-alert-outline", type: "line", source: "mini-risk-alerts", paint: { "line-color": "#b3261e", "line-width": 1.2, "line-opacity": 0.72 } });

      fitDataBounds(map, weatherPoints, alertPoints, Boolean(locationRisk || regional));
    };
    if (map.loaded()) render(); else map.once("load", render);
    return () => { map.off("load", render); };
  }, [alertPoints, compact, locationRisk, regional, weatherPoints, weatherRaster]);

  return (
    <div className={`risk-map-visual real-map ${compact ? "compact" : ""} ${regional ? "regional" : ""}`}>
      <div className="risk-map-canvas" ref={container} aria-label="Live risk outlook map" />
      {!hasLiveData && <div className="risk-map-empty"><div><strong>Live outlook unavailable</strong><small>No hardcoded cities or fabricated weather are shown.</small></div></div>}
      <div className="risk-legend"><strong>Risk level</strong><i /><span>Low</span><span>Extreme</span></div>
      {national && <small className="map-status">{national.active_alerts} active alerts / {national.severe_alerts} severe</small>}
      {locationRisk && <small className="map-status">{locationRisk.place.city}, {locationRisk.place.state} / risk {locationRisk.score}</small>}
    </div>
  );
}

function addRasterLayer(map: Map, weatherRaster: WeatherRasterManifest | null | undefined, beforeLayer: string) {
  if (map.getLayer("mini-weather-raster")) map.removeLayer("mini-weather-raster");
  if (map.getSource("mini-weather-raster")) map.removeSource("mini-weather-raster");
  if (import.meta.env.MODE === "test" || weatherRaster?.source === "playwright") return;
  if (!weatherRaster?.url || weatherRaster.bounds.length < 2) return;
  const [[west, south], [east, north]] = weatherRaster.bounds;
  map.addSource("mini-weather-raster", {
    type: "image",
    url: weatherRaster.url,
    coordinates: [[west, north], [east, north], [east, south], [west, south]],
  });
  map.addLayer({
    id: "mini-weather-raster",
    type: "raster",
    source: "mini-weather-raster",
    paint: { "raster-opacity": 0.58 },
  }, map.getLayer(beforeLayer) ? beforeLayer : undefined);
}

function syncMarkers(map: Map, markers: maplibregl.Marker[], weatherPoints: WeatherRisk[], alertPoints: RiskAlert[]) {
  markers.forEach((marker) => marker.remove());
  markers.length = 0;
  weatherPoints.forEach((point) => {
    const element = createWeatherMarker(point);
    markers.push(new maplibregl.Marker({ element }).setLngLat([point.longitude, point.latitude]).addTo(map));
  });
  alertPoints.filter(hasCoordinates).forEach((alert) => {
    const element = createAlertMarker(alert);
    markers.push(new maplibregl.Marker({ element }).setLngLat([alert.longitude, alert.latitude]).addTo(map));
  });
}

function topWeatherPoints(snapshot: NationalWeatherSnapshot | null, limit: number) {
  return [...(snapshot?.points ?? [])]
    .filter((point) => point.data_status !== "UNAVAILABLE")
    .sort((a, b) => b.risk_score - a.risk_score)
    .slice(0, limit);
}

function createWeatherMarker(point: WeatherRisk) {
  const element = document.createElement("button");
  element.className = `live-risk-point ${riskLevel(point.risk_score)}`;
  element.type = "button";
  element.setAttribute("aria-label", `${point.city} weather risk ${point.risk_score}`);
  const score = document.createElement("b");
  score.className = riskLevel(point.risk_score);
  score.textContent = String(point.risk_score);
  const name = document.createElement("span");
  name.textContent = point.city;
  const detail = document.createElement("small");
  detail.textContent = `${Math.round(point.precipitation_probability)}% rain / ${Math.round(point.wind_speed_mph)} mph`;
  element.append(score, name, detail);
  return element;
}

function createAlertMarker(alert: RiskAlert & { longitude: number; latitude: number }) {
  const element = document.createElement("button");
  element.className = "live-alert-point";
  element.type = "button";
  element.title = alert.event;
  element.setAttribute("aria-label", `${alert.event} alert risk ${alert.score}`);
  return element;
}

function hasCoordinates(alert: RiskAlert): alert is RiskAlert & { longitude: number; latitude: number } {
  return alert.longitude != null && alert.latitude != null;
}

function fitDataBounds(map: Map, weather: WeatherRisk[], alerts: RiskAlert[], regional: boolean) {
  const coordinates: [number, number][] = [
    ...weather.map((point) => [point.longitude, point.latitude] as [number, number]),
    ...alerts.filter(hasCoordinates).map((alert) => [alert.longitude, alert.latitude] as [number, number]),
    ...alerts.flatMap(alertGeometryCoordinates),
  ];
  if (!coordinates.length) {
    map.easeTo({ center: [-98.58, 39.83], zoom: regional ? 5 : 3.2, duration: 0 });
    return;
  }
  const bounds = coordinates.slice(1).reduce((current, coordinate) => current.extend(coordinate), new maplibregl.LngLatBounds(coordinates[0], coordinates[0]));
  map.fitBounds(bounds, { padding: regional ? 50 : 36, maxZoom: regional ? 7 : 4.6, duration: 0 });
}

function alertGeometryCoordinates(alert: RiskAlert) {
  if (!alert.geometry) return [];
  if (alert.geometry.type === "Polygon") {
    return alert.geometry.coordinates.flat() as [number, number][];
  }
  return alert.geometry.coordinates.flat(2) as [number, number][];
}

function riskLevel(score: number) {
  if (score >= 80) return "severe";
  if (score >= 55) return "high";
  if (score >= 30) return "moderate";
  return "low";
}
