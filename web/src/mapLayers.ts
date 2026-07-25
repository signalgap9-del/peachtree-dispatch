export interface MapLayerVisibility {
  heatmap: boolean;
  alertZones: boolean;
  alertMarkers: boolean;
  weatherRaster: boolean;
  routeSegments: boolean;
}

export const DEFAULT_LAYER_VISIBILITY: MapLayerVisibility = {
  heatmap: true,
  alertZones: true,
  alertMarkers: true,
  weatherRaster: true,
  routeSegments: true,
};

export function visibleLayerCount(layers: MapLayerVisibility): number {
  return Object.values(layers).filter(Boolean).length;
}
