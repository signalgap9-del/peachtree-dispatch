package com.freightscaler.ranking.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumer-side view of the bid service's event published to "bid-events".
 * Mirrors com.freightscaler.bid.model.BidEvent; unknown fields are tolerated
 * so the bid service can evolve its schema without breaking ranking.
 * The corridor is not part of the event and is resolved from freight_load.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BidEvent(
        Long bidId,
        Long loadId,
        UUID carrierId,
        String type,
        Integer rateCents,
        String traceId,
        Instant occurredAt
) {
}
