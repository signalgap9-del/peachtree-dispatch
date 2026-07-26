package com.atmospath.platform.compliance.dto;

import java.time.Instant;
import java.util.UUID;

import com.atmospath.platform.saas.entity.SavedRouteEntity;

/** Saved route projection for the GDPR data export. */
public record SavedRouteExport(
        UUID id,
        String name,
        String originName,
        String destinationName,
        String vehicleType,
        int riskThreshold,
        boolean monitorEnabled,
        Instant createdAt) {

    public static SavedRouteExport from(SavedRouteEntity entity) {
        return new SavedRouteExport(
                entity.getId(),
                entity.getName(),
                entity.getOriginName(),
                entity.getDestinationName(),
                entity.getVehicleType(),
                entity.getRiskThreshold(),
                entity.isMonitorEnabled(),
                entity.getCreatedAt());
    }
}
