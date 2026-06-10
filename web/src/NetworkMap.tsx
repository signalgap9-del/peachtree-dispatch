import maplibregl, { type Map } from "maplibre-gl";
import { useEffect, useRef } from "react";

import type { NetworkOverview } from "./types";

interface Props {
  network: NetworkOverview | null;
  selectedRoute: string | null;
  onSelectRoute: (routeId: string) => void;
}

const style = {
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

export function NetworkMap({ network, selectedRoute, onSelectRoute }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const mapRef = useRef<Map | null>(null);
  const markers = useRef<maplibregl.Marker[]>([]);

  useEffect(() => {
    if (!container.current || mapRef.current) return;
    const map = new maplibregl.Map({
      container: container.current,
      style,
      center: [-84.2, 33.42],
      zoom: 7,
      pitch: 28,
      bearing: 0,
      attributionControl: false,
    });
    map.addControl(new maplibregl.NavigationControl({ showCompass: true }), "bottom-right");
    map.addControl(new maplibregl.AttributionControl({ compact: true }), "bottom-right");
    mapRef.current = map;
    return () => {
      map.remove();
      mapRef.current = null;
    };
  }, []);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !network) return;
    const routeListeners: Array<{
      layerId: string;
      selectRoute: () => void;
      showPointer: () => void;
      hidePointer: () => void;
    }> = [];
    const render = () => {
      markers.current.forEach((marker) => marker.remove());
      markers.current = [];

      network.routes.forEach((route) => {
        const sourceId = `route-${route.route_id}`;
        const casingId = `route-casing-${route.route_id}`;
        const layerId = `route-line-${route.route_id}`;
        if (map.getLayer(casingId)) map.removeLayer(casingId);
        if (map.getLayer(layerId)) map.removeLayer(layerId);
        if (map.getSource(sourceId)) map.removeSource(sourceId);
        map.addSource(sourceId, {
          type: "geojson",
          data: {
            type: "Feature",
            properties: { routeId: route.route_id },
            geometry: { type: "LineString", coordinates: route.coordinates },
          },
        });
        map.addLayer({
          id: casingId,
          type: "line",
          source: sourceId,
          layout: { "line-cap": "round", "line-join": "round" },
          paint: {
            "line-color": "#ffffff",
            "line-width": selectedRoute === route.route_id ? 11 : 7,
            "line-opacity": selectedRoute && selectedRoute !== route.route_id ? 0.35 : 0.82,
          },
        });
        map.addLayer({
          id: layerId,
          type: "line",
          source: sourceId,
          layout: { "line-cap": "round", "line-join": "round" },
          paint: {
            "line-color": route.color,
            "line-width": selectedRoute === route.route_id ? 7 : 4,
            "line-opacity": selectedRoute && selectedRoute !== route.route_id ? 0.48 : 0.95,
          },
        });
        const selectRoute = () => onSelectRoute(route.route_id);
        const showPointer = () => {
          map.getCanvas().style.cursor = "pointer";
        };
        const hidePointer = () => {
          map.getCanvas().style.cursor = "";
        };
        map.on("click", layerId, selectRoute);
        map.on("mouseenter", layerId, showPointer);
        map.on("mouseleave", layerId, hidePointer);
        routeListeners.push({ layerId, selectRoute, showPointer, hidePointer });

        route.stops.forEach((stop) => {
          const element = document.createElement("button");
          element.className = `stop-marker ${selectedRoute === route.route_id ? "active" : ""}`;
          element.style.setProperty("--route-color", route.color);
          element.innerHTML = `<strong>${stop.sequence}</strong><span>${stop.address}</span>`;
          element.title = `${stop.sequence}. ${stop.address}`;
          element.addEventListener("click", selectRoute);
          markers.current.push(
            new maplibregl.Marker({ element, anchor: "bottom" })
              .setLngLat([stop.longitude, stop.latitude])
              .addTo(map),
          );
        });
      });

      const depot = document.createElement("button");
      depot.className = "depot-marker";
      depot.innerHTML = "<strong>P</strong><span>Peachtree Dispatch Depot</span>";
      depot.title = "675 Ponce De Leon Ave NE, Atlanta, GA 30308";
      markers.current.push(
        new maplibregl.Marker({ element: depot, anchor: "bottom" })
          .setLngLat([-84.3656, 33.7725])
          .addTo(map),
      );

      network.weather.forEach((weather) => {
        const element = document.createElement("button");
        element.className = `weather-marker ${weather.risk_level.toLowerCase()}`;
        element.innerHTML = `<strong>${weather.risk_score}</strong><span>${weather.city}</span>`;
        element.title = `${weather.city}: ${weather.precipitation_probability}% precipitation risk`;
        markers.current.push(
          new maplibregl.Marker({ element })
            .setLngLat([weather.longitude, weather.latitude])
            .addTo(map),
        );
      });
    };
    if (map.loaded()) render();
    else map.once("load", render);
    return () => {
      map.off("load", render);
      routeListeners.forEach(({ layerId, selectRoute, showPointer, hidePointer }) => {
        map.off("click", layerId, selectRoute);
        map.off("mouseenter", layerId, showPointer);
        map.off("mouseleave", layerId, hidePointer);
      });
    };
  }, [network, onSelectRoute, selectedRoute]);

  useEffect(() => {
    const map = mapRef.current;
    const route = network?.routes.find((item) => item.route_id === selectedRoute);
    if (!map || !route || route.coordinates.length === 0) return;
    const first = route.coordinates[0] as [number, number];
    const bounds = route.coordinates.reduce(
      (current, coordinate) => current.extend(coordinate as [number, number]),
      new maplibregl.LngLatBounds(first, first),
    );
    map.fitBounds(bounds, {
      padding: { top: 110, right: 310, bottom: 130, left: 370 },
      duration: 700,
    });
  }, [network, selectedRoute]);

  return <div className="network-map" ref={container} />;
}
