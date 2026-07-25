package com.atmospath.platform.llm.rag;

import java.time.Instant;
import java.util.UUID;

/**
 * A citation-ready reference to a historical observation that influenced
 * an LLM response. Carries the final relevance score after RRF + rerank.
 */
public record SourceReference(
        UUID observationId,
        String tableName,
        Instant observationTime,
        UUID routeId,
        int riskScore,
        double relevanceScore,
        String summary) {

    /** Returns a display-friendly route identifier. */
    public String routeDisplay() {
        return routeId != null ? routeId.toString() : "unknown";
    }
}
