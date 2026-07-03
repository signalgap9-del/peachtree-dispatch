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
        String generatedAt,
        String usualDepartureTime,
        int riskThreshold,
        boolean monitorEnabled,
        String lastCheckedAt,
        List<String> activeHazards,
        String riskTrend) {
    public SavedRoute(
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
        this(
                savedItemId,
                userId,
                name,
                originName,
                destinationName,
                vehicleType,
                distanceMiles,
                durationMinutes,
                climateDelayMinutes,
                riskScore,
                coordinates,
                generatedAt,
                "08:00",
                55,
                true,
                generatedAt,
                List.of(),
                "STABLE");
    }
}
