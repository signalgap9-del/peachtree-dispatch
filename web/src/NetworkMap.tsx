import maplibregl, { type Map } from "maplibre-gl";
import { useEffect, useRef } from "react";

import type { DirectionsPlan, NationalRiskOverview, NationalWeatherSnapshot, WeatherRasterManifest } from "./types";

interface Props {
  plan: DirectionsPlan | null;
  risk: NationalRiskOverview | null;
  weatherSnapshot: NationalWeatherSnapshot | null;
  weatherRaster: WeatherRasterManifest | null;
  showRisk: boolean;
  recenterToken: number;
}

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

export function NetworkMap({ plan, risk, weatherSnapshot, weatherRaster, showRisk, recenterToken }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const mapRef = useRef<Map | null>(null);
  const markers = useRef<maplibregl.Marker[]>([]);

  useEffect(() => {
    if (!container.current || mapRef.current) return;
    const map = new maplibregl.Map({
      container: container.current,
      style,
      center: [-98.58, 39.83],
      zoom: 3.4,
      pitch: 0,
      attributionControl: false,
    });
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "bottom-right");
    map.addControl(new maplibregl.AttributionControl({ compact: true }), "bottom-right");
    mapRef.current = map;
    return () => { map.remove(); mapRef.current = null; };
  }, []);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const render = () => {
      markers.current.forEach((marker) => marker.remove());
      markers.current = [];
      for (const layer of ["route-main", "route-casing"]) if (map.getLayer(layer)) map.removeLayer(layer);
      if (map.getSource("route")) map.removeSource("route");
      if (!plan) return;
      map.addSource("route", {
        type: "geojson",
        data: { type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: plan.coordinates } },
      });
      map.addLayer({ id: "route-casing", type: "line", source: "route", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#ffffff", "line-width": 10, "line-opacity": 0.92 } });
      map.addLayer({ id: "route-main", type: "line", source: "route", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#1a73e8", "line-width": 6 } });
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
    };
    if (map.loaded()) render(); else map.once("load", render);
    return () => { map.off("load", render); };
  }, [plan]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const render = () => {
      for (const layer of ["risk-alert-outline", "risk-alert-fill", "risk-heat"]) {
        if (map.getLayer(layer)) map.removeLayer(layer);
      }
      for (const source of ["risk-alerts", "risk-points"]) {
        if (map.getSource(source)) map.removeSource(source);
      }
      if (!showRisk || (!risk && !weatherSnapshot)) return;
      const alertPoints = (risk?.alerts ?? [])
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
      const polygons = (risk?.alerts ?? [])
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
    };
    if (map.loaded()) render(); else map.once("load", render);
    return () => { map.off("load", render); };
  }, [risk, showRisk, weatherSnapshot]);

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
