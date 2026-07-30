package com.freightscaler.tracking.model;

import java.time.Instant;
import java.util.UUID;

public record TrackingEvent(
    Instant time,
    UUID truckId,
    String corridorId,
    double lat,
    double lon,
    Double speedKmh,
    Integer heading,
    Short riskScore
) {}
