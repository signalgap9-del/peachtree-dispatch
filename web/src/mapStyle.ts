import type { StyleSpecification } from "maplibre-gl";

/**
 * Base map style selection, shared by every MapLibre map in the app.
 *
 * With VITE_MAPTILER_API_KEY set, maps use MapTiler's vector streets style.
 * Without a key (local development, CI) they fall back to keyless
 * OpenStreetMap raster tiles so the map always renders. MapLibre GL stays
 * the rendering library in both modes.
 */
const maptilerKey = (import.meta.env.VITE_MAPTILER_API_KEY ?? "").trim();

const osmFallbackStyle: StyleSpecification = {
  version: 8,
  sources: {
    osm: {
      type: "raster",
      tiles: ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      tileSize: 256,
      attribution: "© OpenStreetMap contributors",
    },
  },
  layers: [{ id: "osm", type: "raster", source: "osm" }],
};

export const baseMapStyle: string | StyleSpecification = maptilerKey
  ? `https://api.maptiler.com/maps/streets-v2/style.json?key=${encodeURIComponent(maptilerKey)}`
  : osmFallbackStyle;

const testMapStyle: StyleSpecification = {
  version: 8,
  sources: {},
  layers: [
    {
      id: "test-background",
      type: "background",
      paint: { "background-color": "#eef3f8" },
    },
  ],
};

export const mapStyle: string | StyleSpecification =
  import.meta.env.MODE === "test" ? testMapStyle : baseMapStyle;
