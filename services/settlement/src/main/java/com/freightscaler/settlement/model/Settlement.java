package com.freightscaler.settlement.model;

import java.time.Instant;
import java.util.UUID;

public record Settlement(
        Long id,
        Long loadId,
        Long bidId,
        UUID carrierId,
        UUID shipperId,
        int baseRateCents,
        int adjustmentCents,
        Integer finalAmountCents,
        SettlementStatus status,
        int currentStep,
        String sagaLog,
        Instant createdAt,
        Instant completedAt
) {
}
