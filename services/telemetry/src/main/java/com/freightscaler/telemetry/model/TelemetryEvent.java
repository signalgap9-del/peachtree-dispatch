package com.freightscaler.telemetry.model;

import java.time.Instant;
import java.util.UUID;

public record TelemetryEvent(
    UUID truckId,
    double lat,
    double lon,
    double speedKmh,
    Integer heading,
    String corridorId,
    Instant timestamp,
    String traceId,
    Instant ingestedAt
) {}
