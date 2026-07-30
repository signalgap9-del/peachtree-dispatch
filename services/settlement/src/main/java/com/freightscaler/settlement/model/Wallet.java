package com.freightscaler.settlement.model;

import java.time.Instant;
import java.util.UUID;

public record Wallet(
        UUID ownerId,
        long balanceCents,
        int version,
        Instant updatedAt
) {
}
