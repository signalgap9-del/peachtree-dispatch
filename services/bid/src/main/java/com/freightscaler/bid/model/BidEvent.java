package com.freightscaler.bid.model;

import java.time.Instant;
import java.util.UUID;

public record BidEvent(
        Long bidId,
        Long loadId,
        UUID carrierId,
        String type,
        int rateCents,
        String traceId,
        Instant occurredAt
) {
}
