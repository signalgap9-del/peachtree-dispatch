package com.atmospath.platform.llm.rag;

import java.time.Instant;

/**
 * Optional filters that narrow the RAG search scope. All fields are
 * nullable; a {@code null} value means "no constraint". Used by both
 * {@link VectorSearchRepository} and the RAG pipeline.
 */
public record SearchFilters(
        String routeId,
        Instant timeFrom,
        Instant timeTo,
        Integer minRiskScore,
        String severity,
        String tenantId) {

    public static SearchFilters none() {
        return new SearchFilters(null, null, null, null, null, null);
    }

    public boolean hasRouteId() {
        return routeId != null && !routeId.isBlank();
    }

    public boolean hasMinRiskScore() {
        return minRiskScore != null;
    }

    public boolean hasSeverity() {
        return severity != null && !severity.isBlank();
    }

    public boolean hasTenantId() {
        return tenantId != null && !tenantId.isBlank();
    }
}
