package com.atmospath.platform.relational;

import java.util.UUID;

public record SavedPlace(
        UUID savedItemId,
        UUID userId,
        String name,
        double longitude,
        double latitude,
        Integer currentRiskScore) {
}
