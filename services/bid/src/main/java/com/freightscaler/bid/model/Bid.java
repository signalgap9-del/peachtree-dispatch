package com.freightscaler.bid.model;

import java.time.Instant;
import java.util.UUID;

public record Bid(
        Long id,
        Long loadId,
        UUID carrierId,
        int rateCents,
        Double estimatedHours,
        boolean riskAcknowledgment,
        BidStatus status,
        Instant createdAt
) {
}
