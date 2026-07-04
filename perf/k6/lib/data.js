export function findPlace(places, placeId) {
  const place = places.find((candidate) => candidate.place_id === placeId);
  if (!place) {
    throw new Error(`Missing place fixture: ${placeId}`);
  }
  return place;
}

export function deterministicItem(items) {
  if (!items.length) {
    throw new Error("Cannot choose from an empty fixture list.");
  }
  return items[(__VU + __ITER) % items.length];
}

export function routeFixtureToDirectionsPayload(route, places) {
  return {
    origin: findPlace(places, route.origin),
    destination: findPlace(places, route.destination),
    vehicle_type: route.vehicleType
  };
}

export function routeFixtureToMultiStopPayload(route, places, optimize = false) {
  const origin = findPlace(places, route.origin);
  const destination = findPlace(places, route.destination);
  const midpoint = midpointStop(route.id, origin, destination);

  return {
    mode: optimize ? "OPTIMIZE_ORDER" : "MANUAL_ORDER",
    vehicleType: route.vehicleType,
    startStopId: `${route.id}-origin`,
    endStopId: `${route.id}-destination`,
    objective: "risk_adjusted_time",
    riskModel: {
      weatherRiskWeight: 0.35,
      trafficRiskWeight: 0.25,
      floodRiskWeight: 0.3,
      alertRiskWeight: 0.5
    },
    stops: [
      routeStop(`${route.id}-origin`, "DEPOT", origin, 0),
      midpoint,
      routeStop(`${route.id}-destination`, "FINAL", destination, 2)
    ]
  };
}

export function routeFixtureToSavedRoutePayload(route, places) {
  const origin = findPlace(places, route.origin);
  const destination = findPlace(places, route.destination);
  return {
    name: route.label,
    originName: origin.display_name,
    destinationName: destination.display_name,
    vehicleType: route.vehicleType,
    distanceMiles: roughDistanceMiles(origin, destination),
    durationMinutes: roughDistanceMiles(origin, destination),
    climateDelayMinutes: 12,
    riskScore: route.riskThreshold + 3,
    coordinates: [
      [origin.longitude, origin.latitude],
      [destination.longitude, destination.latitude]
    ],
    generatedAt: new Date().toISOString(),
    usualDepartureTime: route.usualDepartureTime,
    riskThreshold: route.riskThreshold,
    monitorEnabled: true,
    activeHazards: route.hazardTags
  };
}

export function graphqlCapabilitiesQuery() {
  return {
    query: `query RouteEngineCapabilities {
      routeEngineCapabilities {
        supportsGraphql
        maxMultiStopStops
        maxVrpJobs
        supportedSolvers
        mlShadowMode
      }
      mlWorkflowStatus {
        mode
        servedToUsers
        activeModelVersion
        featureSchemaVersion
        nextActions
      }
    }`
  };
}

export function graphqlPlanRouteMutation(route, places) {
  return {
    query: `mutation PlanMultiStopRoute($input: MultiStopRouteInput!) {
      planMultiStopRoute(input: $input) {
        routeId
        routeRiskScore
        totalDistanceMiles
        totalDurationMinutes
        riskAdjustedDurationMinutes
        sourceStatus { key value }
        legs {
          sequence
          fromStopId
          toStopId
          riskScore
          primaryHazard
        }
      }
    }`,
    variables: {
      input: routeFixtureToMultiStopPayload(route, places, false)
    }
  };
}

function routeStop(stopId, kind, place, sequence) {
  return {
    stopId,
    kind,
    name: place.display_name,
    latitude: place.latitude,
    longitude: place.longitude,
    city: place.city,
    state: place.state,
    sequence,
    serviceDurationMinutes: 5
  };
}

function midpointStop(routeId, origin, destination) {
  return {
    stopId: `${routeId}-midpoint`,
    kind: "WAYPOINT",
    name: `${origin.city} to ${destination.city} corridor checkpoint`,
    latitude: Number(((origin.latitude + destination.latitude) / 2).toFixed(4)),
    longitude: Number(((origin.longitude + destination.longitude) / 2).toFixed(4)),
    sequence: 1,
    serviceDurationMinutes: 5
  };
}

function roughDistanceMiles(origin, destination) {
  const latMiles = (origin.latitude - destination.latitude) * 69;
  const lonMiles = (origin.longitude - destination.longitude) * 54;
  return Number(Math.max(5, Math.sqrt(latMiles * latMiles + lonMiles * lonMiles)).toFixed(1));
}
