package com.freightscaler.loadboard.service;

import com.freightscaler.loadboard.model.CargoType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record CreateLoadRequest(
        @NotNull UUID tenantId,
        @NotBlank @Size(max = 100) String origin,
        @NotBlank @Size(max = 100) String destination,
        CargoType cargoType,
        @Positive Integer weightKg,
        Instant pickupStart,
        Instant pickupEnd,
        Instant deliveryDeadline,
        @Positive Integer maxRateCents,
        @Size(max = 20) String corridorId,
        Short corridorRisk
) {}
