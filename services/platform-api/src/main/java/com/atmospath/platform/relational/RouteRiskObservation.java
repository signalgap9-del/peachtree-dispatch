package com.atmospath.platform.relational;

import java.util.List;
import java.util.UUID;

public record RouteRiskObservation(
        UUID observationId,
        UUID userId,
        UUID savedItemId,
        String observedAt,
        int riskScore,
        String riskTrend,
        List<String> activeHazards,
        String source,
        String modelVersion) {
    public static RouteRiskObservation fromRoute(SavedRoute route, String source) {
        return new RouteRiskObservation(
                UUID.randomUUID(),
                route.userId(),
                route.savedItemId(),
                route.lastCheckedAt(),
                route.riskScore(),
                route.riskTrend(),
                route.activeHazards(),
                source,
                "route-risk-v1");
    }
}
