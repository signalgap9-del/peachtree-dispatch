package com.freightscaler.loadboard.model;

import java.time.Instant;
import java.util.UUID;

public record Load(
        Long id,
        UUID tenantId,
        String origin,
        String destination,
        CargoType cargoType,
        Integer weightKg,
        Instant pickupStart,
        Instant pickupEnd,
        Instant deliveryDeadline,
        Integer maxRateCents,
        String corridorId,
        Short corridorRisk,
        LoadStatus status,
        int version,
        Instant createdAt,
        Instant updatedAt
) {}
