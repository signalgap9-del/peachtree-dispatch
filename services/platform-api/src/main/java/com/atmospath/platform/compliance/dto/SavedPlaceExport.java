package com.atmospath.platform.compliance.dto;

import java.time.Instant;
import java.util.UUID;

import com.atmospath.platform.saas.entity.SavedPlaceEntity;

/** Saved place projection for the GDPR data export. */
public record SavedPlaceExport(
        UUID id,
        String name,
        Double latitude,
        Double longitude,
        Instant createdAt) {

    public static SavedPlaceExport from(SavedPlaceEntity entity) {
        return new SavedPlaceExport(
                entity.getId(),
                entity.getName(),
                entity.getLatitude(),
                entity.getLongitude(),
                entity.getCreatedAt());
    }
}
