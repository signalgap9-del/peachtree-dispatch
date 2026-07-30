package com.freightscaler.settlement.model;

import java.time.Instant;

/**
 * Kafka event published to the settlement-events topic.
 * Key = settlementId for per-settlement partition ordering.
 */
public record SettlementEvent(
        Long settlementId,
        Long loadId,
        String type,
        String step,
        String detail,
        String traceId,
        Instant occurredAt
) {
}
