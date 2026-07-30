package com.freightscaler.ranking.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the periodic ranking snapshot persisted to PostgreSQL.
 * category is "overall" (corridorId null) or "corridor" (corridorId set).
 */
public record RankingSnapshot(
        Instant snapshotTime,
        String category,
        String corridorId,
        UUID carrierId,
        double score,
        int rank
) {
}
