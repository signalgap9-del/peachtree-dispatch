package com.freightscaler.ranking.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * Contract for shipment lifecycle events on "shipment-events".
 * No producer service exists yet; ranking only scores type=DELIVERED
 * (+5 on time, -3 late). Unknown fields are tolerated so the future
 * producer can add data without breaking this consumer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShipmentEvent(
        String shipmentId,
        UUID carrierId,
        String corridorId,
        String type,
        Boolean onTime,
        String traceId,
        Instant occurredAt
) {
}
