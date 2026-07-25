package com.atmospath.platform.llm.rag;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A single observation returned by the hybrid search layer before
 * reranking. Carries the raw relevance score from RRF fusion.
 * Field order matches {@link VectorSearchRepository} row mapping.
 */
public record SearchResult(
        UUID observationId,
        String tableName,
        double score,
        int riskScore,
        String factorsText,
        Instant observationTime,
        UUID routeId,
        Map<String, Object> metadata) {

    public SearchResult {
        if (metadata == null) {
            metadata = Map.of();
        }
    }

    /** Returns a one-line summary derived from factors text. */
    public String summary() {
        return factorsText != null ? factorsText : "";
    }
}
