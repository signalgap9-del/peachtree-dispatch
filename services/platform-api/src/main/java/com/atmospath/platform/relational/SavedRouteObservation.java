package com.atmospath.platform.relational;

import java.util.List;
import java.util.UUID;

public record SavedRouteObservation(
        UUID observationId,
        UUID savedItemId,
        UUID userId,
        String observedAt,
        double plannedDurationMinutes,
        double actualDurationMinutes,
        double delayMinutes,
        int observedRiskScore,
        List<String> encounteredHazards,
        String weatherSummary,
        String roadEventSummary,
        String source,
        String notes,
        String featureSchemaVersion) {
}
