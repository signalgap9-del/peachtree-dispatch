import maplibregl, { type Map as MaplibreMap } from "maplibre-gl";
import { useEffect, useMemo, useRef, useState } from "react";

import { freightApi, openTrackingSocket } from "../freightApi";
import { CORRIDORS, type TrackingEvent } from "../freightTypes";
import { useI18n } from "../i18n";
import { mapStyle } from "../mapStyle";

type SocketStatus = "open" | "closed" | "error";
type FeedStatus = "loading" | "ready" | "error";

// Risk bands match the app-wide alert scale: <40 green, 40-70 amber, >70 red.
const RISK_COLORS = { low: "#188038", medium: "#f9ab00", high: "#d93025", unknown: "#9aa0a6" } as const;

function riskColor(score: number | null): string {
  if (score === null) return RISK_COLORS.unknown;
  if (score > 70) return RISK_COLORS.high;
  if (score >= 40) return RISK_COLORS.medium;
  return RISK_COLORS.low;
}

function riskChipClass(score: number | null): string {
  if (score === null) return "chip--muted";
  if (score > 70) return "chip--red";
  if (score >= 40) return "chip--amber";
  return "chip--green";
}

function shortTruckId(truckId: string): string {
  return truckId.length > 12 ? `${truckId.slice(0, 10)}…` : truckId;
}

function formatSpeed(speedKmh: number | null): string {
  return speedKmh === null ? "—" : `${Math.round(speedKmh)} km/h`;
}

/** Merge a telemetry batch into the latest-position map, keeping the newest reading per truck. */
function mergeEvents(previous: Map<string, TrackingEvent>, events: TrackingEvent[]): Map<string, TrackingEvent> {
  const next = new Map(previous);
  for (const event of events) {
    const existing = next.get(event.truckId);
    if (!existing || event.time >= existing.time) next.set(event.truckId, event);
  }
  return next;
}

// Map dots are raw DOM elements owned by MapLibre, so they are the one place
// where inline styling is used instead of freight.css classes.
function styleTruckDot(element: HTMLElement, riskScore: number | null, selected: boolean): void {
  element.style.width = "12px";
  element.style.height = "12px";
  element.style.borderRadius = "50%";
  element.style.border = "2px solid #fff";
  element.style.cursor = "pointer";
  element.style.background = riskColor(riskScore);
  element.style.boxShadow = selected
    ? "0 0 0 4px rgba(11, 87, 208, 0.28), 0 1px 4px rgba(32, 33, 36, 0.35)"
    : "0 1px 4px rgba(32, 33, 36, 0.35)";
  element.style.transform = selected ? "scale(1.35)" : "scale(1)";
  element.style.transition = "transform 0.15s ease, box-shadow 0.15s ease, background 0.3s ease";
}

export function FleetPage({ navigate }: { navigate: (path: string) => void }) {
  const { t } = useI18n();
  const mapContainer = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<MaplibreMap | null>(null);
  const markersRef = useRef<Map<string, maplibregl.Marker>>(new Map());

  const [corridor, setCorridor] = useState<string>(CORRIDORS[0]);
  const [positions, setPositions] = useState<Map<string, TrackingEvent>>(() => new Map());
  const [socketStatus, setSocketStatus] = useState<SocketStatus>("closed");
  const [feedStatus, setFeedStatus] = useState<FeedStatus>("loading");
  const [selectedTruckId, setSelectedTruckId] = useState<string | null>(null);

  // Map lifecycle: created once against the flush layout, torn down on unmount.
  useEffect(() => {
    const host = mapContainer.current;
    const markers = markersRef.current;
    if (!host || mapRef.current) return;
    const map = new maplibregl.Map({
      container: host,
      style: mapStyle,
      center: [-98, 39],
      zoom: 3,
      pitch: 0,
      attributionControl: false,
    });
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "bottom-right");
    map.addControl(new maplibregl.AttributionControl({ compact: true }), "bottom-right");
    // Clicking open water/land clears the current selection.
    map.on("click", () => setSelectedTruckId(null));
    mapRef.current = map;
    return () => {
      for (const marker of markers.values()) marker.remove();
      markers.clear();
      map.remove();
      mapRef.current = null;
    };
  }, []);

  // Corridor feed: seed positions from the REST snapshot, then keep them
  // fresh over the tracking WebSocket. A dead socket never crashes the page;
  // the status chip simply stays in the connecting state.
  useEffect(() => {
    let cancelled = false;
    setFeedStatus("loading");
    setSocketStatus("closed");
    setSelectedTruckId(null);
    setPositions(new Map());

    const closeSocket = openTrackingSocket(
      { corridor },
      (events) => {
        if (cancelled || events.length === 0) return;
        setFeedStatus("ready");
        setPositions((previous) => mergeEvents(previous, events));
      },
      (status) => {
        if (!cancelled) setSocketStatus(status);
      },
    );

    freightApi.corridorActive(corridor)
      .then((events) => {
        if (cancelled) return;
        setPositions((previous) => mergeEvents(previous, events));
        setFeedStatus("ready");
      })
      .catch(() => {
        if (!cancelled) setFeedStatus("error");
      });

    return () => {
      cancelled = true;
      closeSocket();
    };
  }, [corridor]);

  // Keep exactly one dot per truck in sync with the latest positions,
  // recoloring by risk and removing trucks that left the corridor.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const markers = markersRef.current;
    const active = new Set<string>();
    for (const [truckId, event] of positions) {
      active.add(truckId);
      const selected = truckId === selectedTruckId;
      const existing = markers.get(truckId);
      if (existing) {
        existing.setLngLat([event.lon, event.lat]);
        styleTruckDot(existing.getElement(), event.riskScore, selected);
        continue;
      }
      const element = document.createElement("div");
      element.title = truckId;
      element.addEventListener("click", (clickEvent) => {
        clickEvent.stopPropagation();
        setSelectedTruckId((current) => (current === truckId ? null : truckId));
      });
      styleTruckDot(element, event.riskScore, selected);
      markers.set(truckId, new maplibregl.Marker({ element }).setLngLat([event.lon, event.lat]).addTo(map));
    }
    for (const [truckId, marker] of markers) {
      if (!active.has(truckId)) {
        marker.remove();
        markers.delete(truckId);
      }
    }
  }, [positions, selectedTruckId]);

  const trucks = useMemo(
    () => [...positions.values()].sort((a, b) => a.truckId.localeCompare(b.truckId)),
    [positions],
  );
  const highRiskCount = useMemo(
    () => trucks.reduce((count, truck) => ((truck.riskScore ?? 0) > 70 ? count + 1 : count), 0),
    [trucks],
  );
  const selectedTruck = selectedTruckId ? positions.get(selectedTruckId) ?? null : null;
  const live = socketStatus === "open";

  const selectTruck = (truck: TrackingEvent) => {
    const selecting = truck.truckId !== selectedTruckId;
    setSelectedTruckId(selecting ? truck.truckId : null);
    const map = mapRef.current;
    if (selecting && map) {
      map.easeTo({ center: [truck.lon, truck.lat], zoom: Math.max(map.getZoom(), 5), duration: 600 });
    }
  };

  return (
    <main className="freight-page freight-page--flush">
      <div className="fleet-shell">
        <div className="fleet-map" ref={mapContainer} role="application" aria-label={t("freight.fleet.title")} />

        <aside className="fleet-panel">
          <header className="freight-head">
            <div>
              <h1>{t("freight.fleet.title")}</h1>
              <p>{t("freight.fleet.subtitle")}</p>
            </div>
            <span className={live ? "chip chip--green" : "chip chip--muted"} role="status">
              {live ? t("freight.fleet.live") : t("freight.fleet.connecting")}
            </span>
          </header>

          <div className="freight-toolbar">
            <select
              className="freight-select"
              value={corridor}
              aria-label={t("freight.fleet.corridors")}
              onChange={(event) => setCorridor(event.target.value)}
            >
              {CORRIDORS.map((corridorId) => (
                <option key={corridorId} value={corridorId}>{corridorId}</option>
              ))}
            </select>
          </div>

          <div className="freight-stats">
            <div className="freight-stat">
              <strong>{trucks.length}</strong>
              <span>{t("freight.fleet.active")}</span>
            </div>
            <div className="freight-stat">
              <strong>{highRiskCount}</strong>
              <span>{t("freight.fleet.highRisk")}</span>
            </div>
          </div>

          {feedStatus === "error" && trucks.length === 0 ? (
            <div className="freight-empty">{t("freight.error")}</div>
          ) : feedStatus !== "ready" && trucks.length === 0 ? (
            <div className="freight-loading">
              <span className="freight-spinner" aria-hidden="true" />
              <span role="status">{t("freight.loading")}</span>
            </div>
          ) : (
            <div>
              {trucks.map((truck) => (
                <button
                  key={truck.truckId}
                  type="button"
                  className={truck.truckId === selectedTruckId ? "fleet-truck-row active" : "fleet-truck-row"}
                  aria-pressed={truck.truckId === selectedTruckId}
                  onClick={() => selectTruck(truck)}
                >
                  <span>
                    <span className="fleet-truck-id">{shortTruckId(truck.truckId)}</span>{" "}
                    <span className="fleet-truck-sub">{truck.corridorId ?? corridor} · {formatSpeed(truck.speedKmh)}</span>
                  </span>
                  <span className={`chip ${riskChipClass(truck.riskScore)}`}>
                    <span className="mono">{truck.riskScore ?? "—"}</span>
                  </span>
                </button>
              ))}
            </div>
          )}
        </aside>

        <section className="fleet-selected-card">
          {selectedTruck ? (
            <>
              <div className="freight-card-route">
                <span className="fleet-truck-id">{selectedTruck.truckId}</span>
                <span className="arrow" aria-hidden="true">·</span>
                <span className="fleet-truck-sub">{selectedTruck.corridorId ?? corridor}</span>
              </div>
              <p className="freight-card-meta">
                <span>{t("freight.fleet.speed")} <span className="mono">{formatSpeed(selectedTruck.speedKmh)}</span></span>
                <span>{t("freight.fleet.risk")} <span className={`chip ${riskChipClass(selectedTruck.riskScore)}`}><span className="mono">{selectedTruck.riskScore ?? "—"}</span></span></span>
              </p>
              <div className="freight-card-actions">
                <button type="button" className="fbtn fbtn--primary" onClick={() => navigate("/map")}>
                  {t("freight.fleet.viewRoute")}
                </button>
              </div>
            </>
          ) : (
            <p className="fleet-truck-sub">{t("freight.fleet.noSelection")}</p>
          )}
        </section>
      </div>
    </main>
  );
}
