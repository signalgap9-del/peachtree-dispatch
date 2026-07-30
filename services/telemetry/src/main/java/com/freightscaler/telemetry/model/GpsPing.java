package com.freightscaler.telemetry.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record GpsPing(
    @NotNull UUID truckId,
    @NotNull @Min(-90) @Max(90) Double lat,
    @NotNull @Min(-180) @Max(180) Double lon,
    @NotNull @Min(0) Double speedKmh,
    @Min(0) @Max(359) Integer heading,
    String corridorId,
    @NotNull Instant timestamp
) {}
