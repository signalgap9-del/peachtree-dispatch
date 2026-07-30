package com.freightscaler.ranking.model;

import java.util.UUID;

/**
 * A carrier's live ranking entry as read from Redis.
 * rank is 1-based within the requested category/corridor.
 */
public record CarrierScore(
        UUID carrierId,
        double score,
        long rank
) {
}
