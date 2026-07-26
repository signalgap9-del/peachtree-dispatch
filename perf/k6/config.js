// Shared k6 configuration for FreightScaler load tests.
//
// BASE_URL must point to a STAGING deployment. Never run these scripts
// against production. See README.md for the full safety checklist.

export const BASE_URL = __ENV.BASE_URL || 'https://staging.freightscaler.com';

// Cost guard: hard ceiling on virtual users to prevent runaway Lambda /
// API Gateway charges on the staging account.
export const MAX_VUS = parseInt(__ENV.MAX_VUS || '50', 10);

// Default test duration for individual scenario scripts.
export const DURATION = __ENV.DURATION || '2m';

// Representative US freight corridor endpoints used across scenarios.
export const PLACES = {
  seattle: {
    place_id: 'k6-seattle',
    display_name: 'Seattle, Washington, United States',
    city: 'Seattle',
    state: 'Washington',
    latitude: 47.6062,
    longitude: -122.3321,
  },
  miami: {
    place_id: 'k6-miami',
    display_name: 'Miami, Florida, United States',
    city: 'Miami',
    state: 'Florida',
    latitude: 25.7617,
    longitude: -80.1918,
  },
  atlanta: {
    place_id: 'k6-atlanta',
    display_name: 'Atlanta, Georgia, United States',
    city: 'Atlanta',
    state: 'Georgia',
    latitude: 33.749,
    longitude: -84.388,
  },
  dallas: {
    place_id: 'k6-dallas',
    display_name: 'Dallas, Texas, United States',
    city: 'Dallas',
    state: 'Texas',
    latitude: 32.7767,
    longitude: -96.797,
  },
  chicago: {
    place_id: 'k6-chicago',
    display_name: 'Chicago, Illinois, United States',
    city: 'Chicago',
    state: 'Illinois',
    latitude: 41.8781,
    longitude: -87.6298,
  },
};

// Standard thresholds shared by most scenarios.
// Individual scenarios may override or extend these.
export const THRESHOLDS = {
  // Cached reads (health, national risk) should be fast.
  'http_req_duration{endpoint:cached_read}': ['p(95)<500'],
  // Route planning involves external calls; allow more headroom.
  'http_req_duration{endpoint:route_planning}': ['p(95)<2000'],
  // Overall error budget.
  'http_req_failed': ['rate<0.01'],
};

// Tag helper: returns a params object with the endpoint tag set.
export function tagged(endpointName) {
  return { tags: { endpoint: endpointName } };
}
