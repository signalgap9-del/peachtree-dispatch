import {
  ArrowDownUp,
  Car,
  CloudRain,
  Flame,
  Layers3,
  LocateFixed,
  MapPin,
  Navigation,
  ShieldAlert,
  Truck,
  X,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";

import type { Navigate } from "./App";
import { api } from "./api";
import { currentUser, login } from "./auth";
import { NetworkMap } from "./NetworkMap";
import type { DirectionsPlan, LocationRisk, NationalRiskOverview, NationalWeatherSnapshot, Place, RouteAlternative, VehicleType, WeatherRasterManifest } from "./types";
import { notify } from "./ui";

type Field = "origin" | "destination";
type Alternative = "fastest" | "lower" | "balanced";

export function MapPage({ navigate, national, weatherSnapshot, weatherRaster }: { navigate: Navigate; national: NationalRiskOverview | null; weatherSnapshot: NationalWeatherSnapshot | null; weatherRaster: WeatherRasterManifest | null }) {
  const [origin, setOrigin] = useState<Place | null>(null);
  const [destination, setDestination] = useState<Place | null>(null);
  const [originQuery, setOriginQuery] = useState("");
  const [destinationQuery, setDestinationQuery] = useState(() => new URLSearchParams(window.location.search).get("search") ?? "");
  const [activeField, setActiveField] = useState<Field>("destination");
  const [results, setResults] = useState<Place[]>([]);
  const [plan, setPlan] = useState<DirectionsPlan | null>(null);
  const [vehicleType, setVehicleType] = useState<VehicleType>("CAR");
  const [loading, setLoading] = useState(false);
  const [showWeather, setShowWeather] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [recenterToken, setRecenterToken] = useState(0);
  const [selectedRisk, setSelectedRisk] = useState<LocationRisk | null>(null);
  const [showRisk, setShowRisk] = useState(true);
  const [selectedAlternative, setSelectedAlternative] = useState<Alternative>("lower");
  const [showWhy, setShowWhy] = useState(false);

  const query = activeField === "origin" ? originQuery : destinationQuery;
  useEffect(() => {
    const selectedPlace = activeField === "origin" ? origin : destination;
    if (selectedPlace && query === selectedPlace.display_name) {
      setResults([]);
      return;
    }
    if (query.trim().length < 2) {
      setResults([]);
      return;
    }
    let cancelled = false;
    const timer = window.setTimeout(async () => {
      try {
        const found = await api.searchPlaces(query);
        if (!cancelled) setResults(found);
      } catch {
        if (!cancelled) setResults([]);
      }
    }, 350);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [activeField, destination, origin, query]);

  const calculate = useCallback(async () => {
    if (!origin || !destination) return;
    setLoading(true);
    setError(null);
    try {
      setPlan(await api.directions(origin, destination, vehicleType));
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Unable to calculate directions");
    } finally {
      setLoading(false);
    }
  }, [destination, origin, vehicleType]);

  useEffect(() => void calculate(), [calculate]);

  const alternatives = useMemo(() => {
    if (!plan) return [];
    return plan.alternatives?.length ? plan.alternatives : [{
      alternative_id: "route-1", label: "Fastest", coordinates: plan.coordinates,
      distance_miles: plan.distance_miles, duration_minutes: plan.duration_minutes,
      climate_delay_minutes: plan.climate_delay_minutes, risk_score: plan.risk_score,
      weather: plan.weather, hazards: [],
    }];
  }, [plan]);
  const selectedRoute = alternatives.find((alternative) => alternativeKind(alternative) === selectedAlternative) ?? alternatives[0];
  const displayedPlan = plan && selectedRoute ? {
    ...plan,
    coordinates: selectedRoute.coordinates,
    distance_miles: selectedRoute.distance_miles,
    duration_minutes: selectedRoute.duration_minutes,
    climate_delay_minutes: selectedRoute.climate_delay_minutes,
    risk_score: selectedRoute.risk_score,
    weather: selectedRoute.weather,
  } : plan;
  useEffect(() => {
    if (!alternatives.length) return;
    setSelectedAlternative(alternatives.some((alternative) => alternativeKind(alternative) === "lower") ? "lower" : alternativeKind(alternatives[0]));
  }, [alternatives]);

  function choosePlace(place: Place) {
    if (activeField === "origin") {
      setOrigin(place);
      setOriginQuery(place.display_name);
      setActiveField("destination");
    } else {
      setDestination(place);
      setDestinationQuery(place.display_name);
    }
    setResults([]);
    void api.locationRisk(place).then(setSelectedRisk).catch(() => setSelectedRisk(null));
  }

  async function saveSelectedTrip() {
    if (!displayedPlan) {
      notify("Choose an origin and destination first.");
      return;
    }
    if (!currentUser()) {
      notify("Sign in to save this route to your account.");
      void login();
      return;
    }
    try {
      await api.saveRoute(displayedPlan);
      notify("Route saved to your account.");
      navigate("/saved");
    } catch {
      notify("This route could not be saved.");
    }
  }

  return (
    <main className="map-page">
      <NetworkMap plan={showWeather ? displayedPlan : displayedPlan && { ...displayedPlan, weather: [] }} risk={national} weatherSnapshot={showWeather ? weatherSnapshot : null} weatherRaster={showWeather ? weatherRaster : null} showRisk={showRisk} recenterToken={recenterToken} />
      <aside className="directions-panel">
        <div className="directions-head">
          <div><span>Trip</span><h1>{plan ? plan.summary : "Plan a climate-aware route"}</h1></div>
          <button className="close-panel" aria-label="Clear route" onClick={() => { setPlan(null); setDestination(null); setDestinationQuery(""); }}><X size={18} /></button>
        </div>
        <VehicleModes value={vehicleType} onChange={setVehicleType} />
        <div className="route-inputs">
          <div className="route-rail"><i className="origin-dot" /><span /><i className="destination-dot" /></div>
          <div className="input-stack">
            <label className={activeField === "origin" ? "active" : ""}><input value={originQuery} onFocus={() => setActiveField("origin")} onChange={(event) => { setOriginQuery(event.target.value); setOrigin(null); setActiveField("origin"); }} placeholder="Choose starting point" /></label>
            <label className={activeField === "destination" ? "active" : ""}><input value={destinationQuery} onFocus={() => setActiveField("destination")} onChange={(event) => { setDestinationQuery(event.target.value); setDestination(null); setActiveField("destination"); }} placeholder="Choose destination" /></label>
          </div>
          <button className="swap-button" aria-label="Swap origin and destination" onClick={() => { setOrigin(destination); setDestination(origin); setOriginQuery(destination?.display_name ?? ""); setDestinationQuery(origin?.display_name ?? ""); }}><ArrowDownUp size={17} /></button>
        </div>
        {results.length > 0 && <div className="place-results">{results.map((place) => <button key={place.place_id} onClick={() => choosePlace(place)}><i><MapPin size={16} /></i><div><strong>{place.display_name.split(",")[0]}</strong><span>{place.display_name.split(",").slice(1).join(",")}</span></div></button>)}</div>}
        {!plan && results.length === 0 && <div className="empty-directions"><Navigation size={24} /><strong>Compare time against weather risk</strong><p>Search any U.S. city, address, landmark, or highway corridor.</p></div>}
        {plan && (
          <section className="route-alternatives">
            <div className="section-label"><strong>Route options</strong><button onClick={() => setShowWhy((value) => !value)}>Why these routes?</button></div>
            {showWhy && <p className="route-explanation">Alternatives balance travel time with live precipitation, wind, heat, and active NWS alerts. Lower-risk routes may take longer.</p>}
            {alternatives.map((alternative) => (
              <button key={alternative.alternative_id} className={`alternative-card ${selectedAlternative === alternativeKind(alternative) ? "selected" : ""}`} onClick={() => setSelectedAlternative(alternativeKind(alternative))}>
                <span className="alternative-top"><strong>{alternative.label}</strong><em>{formatDuration(alternative.duration_minutes)}</em></span>
                <span className="alternative-meta"><b>{Math.round(alternative.distance_miles).toLocaleString()} mi</b><i className={alternative.confidence === "UNAVAILABLE" ? "moderate" : riskClassFromScore(alternative.risk_score)}>{alternative.confidence === "UNAVAILABLE" ? "Risk unavailable" : `Risk ${alternative.risk_score}`}</i><small>{Math.round((alternative.data_coverage ?? 0) * 100)}% coverage</small></span>
                <span className="alternative-note">{alternative.hazards.length ? alternative.hazards.map((hazard) => hazard.category.replaceAll("_", " ")).join(" / ") : "No active route hazard alerts"}</span>
              </button>
            ))}
            <button className="text-action" onClick={() => void saveSelectedTrip()}>Save this trip</button>
          </section>
        )}
        {loading && <div className="route-loading">Calculating nationwide route...</div>}
        {error && <div className="route-error">{error}</div>}
      </aside>
      <div className="map-actions">
        <button aria-label="Show United States" onClick={() => setRecenterToken((value) => value + 1)}><LocateFixed size={19} /></button>
        <button aria-label="Toggle nationwide risk heatmap" className={showRisk ? "active" : ""} onClick={() => setShowRisk((value) => !value)}><Layers3 size={19} /></button>
        <button aria-label="Toggle weather layer" className={showWeather ? "active" : ""} onClick={() => setShowWeather((value) => !value)}><CloudRain size={19} /></button>
      </div>
      <RiskInspector national={national} selected={selectedRisk} plan={displayedPlan} route={selectedRoute} selectedAlternative={selectedAlternative} showWeather={showWeather} setShowWeather={setShowWeather} navigate={navigate} />
    </main>
  );
}

function RiskInspector({ national, selected, plan, route, selectedAlternative, showWeather, setShowWeather, navigate }: { national: NationalRiskOverview | null; selected: LocationRisk | null; plan: DirectionsPlan | null; route?: RouteAlternative; selectedAlternative: Alternative; showWeather: boolean; setShowWeather: (value: boolean) => void; navigate: Navigate }) {
  return (
    <aside className="risk-dashboard">
      <div className="risk-dashboard-head"><div><span>{plan ? "Selected route" : "Live U.S. risk"}</span><h2>{plan ? selectedAlternativeLabel(selectedAlternative) : selected ? selected.place.city : "National outlook"}</h2></div><ShieldAlert size={20} /></div>
      <div className={`risk-score ${riskClass(plan ? riskClassFromScore(plan.risk_score) : selected?.level ?? national?.level)}`}><strong>{plan?.risk_score ?? selected?.score ?? national?.score ?? "–"}</strong><div><span>{plan ? "Route risk" : selected?.level ?? national?.level ?? "Loading"}</span><small>{plan ? `${plan.weather.filter((sample) => sample.data_status !== "UNAVAILABLE").length}/${plan.weather.length} route checkpoints live` : selected?.summary ?? `${national?.active_alerts ?? 0} active NWS alerts`}</small></div></div>
      {plan && <div className="inspector-route"><strong>Risk by route checkpoint</strong><p>Live provider checkpoints used by model {route?.model_version ?? plan.model_version ?? "route-risk"}.</p>{sampledWeather(plan).map((sample) => <div key={sample.id}><i className={sample.data_status === "UNAVAILABLE" ? "high" : riskClassFromScore(sample.risk_score)} /><span><strong>{sample.city}</strong><small>{sample.data_status === "UNAVAILABLE" ? "Weather provider unavailable" : `${Math.round(sample.precipitation_probability)}% precipitation / ${Math.round(sample.wind_speed_mph)} mph wind`}</small></span><em>{sample.data_status === "UNAVAILABLE" ? "N/A" : sample.risk_score}</em></div>)}</div>}
      {!plan && <div className="national-stats"><div><strong>{national?.active_alerts ?? "–"}</strong><span>Active alerts</span></div><div><strong>{national?.severe_alerts ?? "–"}</strong><span>Severe</span></div><div><strong>{national?.alerts_with_geometry ?? "–"}</strong><span>Mapped</span></div></div>}
      {selected && <div className="factor-grid"><RiskFactor label="Flood" value={selected.factors.flood} icon={<CloudRain size={14} />} /><RiskFactor label="Rain" value={selected.factors.precipitation} icon={<CloudRain size={14} />} /><RiskFactor label="Wind" value={selected.factors.wind} icon={<Navigation size={14} />} /><RiskFactor label="Heat" value={selected.factors.heat} icon={<Flame size={14} />} /></div>}
      <div className="inspector-actions"><button className={showWeather ? "active" : ""} onClick={() => setShowWeather(!showWeather)}>Weather layer</button><button onClick={() => navigate("/alerts")}>View alerts</button></div>
      <footer>NWS alerts: {route?.source_status?.nws_alerts ?? selected?.source_status?.nws_alerts ?? national?.source_status?.nws_alerts ?? "loading"} / Weather: {route?.source_status?.weather ?? selected?.source_status?.weather ?? "loading"}</footer>
    </aside>
  );
}

function sampledWeather(plan: DirectionsPlan) {
  if (plan.weather.length <= 4) return plan.weather;
  return [plan.weather[0], plan.weather[Math.floor(plan.weather.length / 2)], plan.weather.at(-1)!];
}

function RiskFactor({ label, value, icon }: { label: string; value: number; icon: React.ReactNode }) {
  return <div><span>{icon}{label}</span><strong>{value}</strong><i><b style={{ width: `${value}%` }} /></i></div>;
}

function VehicleModes({ value, onChange }: { value: VehicleType; onChange: (value: VehicleType) => void }) {
  return <div className="travel-modes">{(["CAR", "VAN", "TRUCK"] as VehicleType[]).map((mode) => <button key={mode} className={value === mode ? "active" : ""} onClick={() => onChange(mode)}>{mode === "CAR" ? <Car size={18} /> : <Truck size={18} />}<span>{mode.charAt(0) + mode.slice(1).toLowerCase()}</span></button>)}</div>;
}

function formatDuration(minutes: number) {
  const rounded = Math.round(minutes);
  return `${Math.floor(rounded / 60)} hr ${rounded % 60} min`;
}

function riskClass(level?: string) {
  return (level ?? "low").toLowerCase();
}

function riskClassFromScore(score: number) {
  if (score >= 80) return "severe";
  if (score >= 55) return "high";
  if (score >= 30) return "moderate";
  return "low";
}

function selectedAlternativeLabel(alternative: Alternative) {
  return alternative === "lower" ? "Lower weather risk" : alternative === "balanced" ? "Balanced route" : "Fastest route";
}

function alternativeKind(alternative: RouteAlternative): Alternative {
  if (alternative.label === "Lower weather risk") return "lower";
  if (alternative.label === "Balanced") return "balanced";
  return "fastest";
}
