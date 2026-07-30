package com.freightscaler.bid.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record BidSubmission(
        @NotNull Long loadId,
        @NotNull UUID carrierId,
        @NotNull @Positive int rateCents,
        Double estimatedHours,
        boolean riskAcknowledgment
) {
}
