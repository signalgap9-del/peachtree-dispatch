package com.freightscaler.loadboard.model;

import java.time.Instant;

public record LoadEvent(
        Long loadId,
        String type,
        String corridorId,
        String cargoType,
        Integer maxRateCents,
        Short corridorRisk,
        String traceId,
        Instant occurredAt
) {}
