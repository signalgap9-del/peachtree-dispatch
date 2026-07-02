package com.atmospath.platform.relational;

import java.util.List;
import java.util.UUID;

public record SavedRoute(
        UUID savedItemId,
        UUID userId,
        String name,
        String originName,
        String destinationName,
        String vehicleType,
        double distanceMiles,
        double durationMinutes,
        double climateDelayMinutes,
        int riskScore,
        List<List<Double>> coordinates,
        String generatedAt) {
}
