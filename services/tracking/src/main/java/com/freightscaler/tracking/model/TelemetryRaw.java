package com.freightscaler.tracking.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Incoming Kafka message from the telemetry service.
 * Matches the output schema published to the "telemetry-raw" topic.
 */
public record TelemetryRaw(
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
