package com.atmospath.platform.relational;

import java.util.List;
import java.util.UUID;

public record SavedRouteTrainingExample(
        UUID observationId,
        UUID savedItemId,
        String featureSchemaVersion,
        String vehicleType,
        double distanceMiles,
        double plannedDurationMinutes,
        double climateDelayMinutes,
        int plannedRiskScore,
        String generatedAt,
        String observedAt,
        double actualDurationMinutes,
        double delayLabelMinutes,
        int observedRiskScore,
        List<String> plannedHazards,
        List<String> encounteredHazards,
        String weatherSummary,
        String roadEventSummary,
        String source) {
}
