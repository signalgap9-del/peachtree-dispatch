package com.atmospath.platform.saas.entity;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Identity of a TimescaleDB hypertable row: the hypertable has no surrogate
 * key, so (time, saved_route_id) identifies an observation.
 */
public record RouteRiskObservationId(Instant time, UUID savedRouteId) implements Serializable {
}
