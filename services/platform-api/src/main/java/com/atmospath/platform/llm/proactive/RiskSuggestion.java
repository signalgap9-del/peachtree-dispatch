package com.atmospath.platform.llm.proactive;

import java.time.Instant;
import java.util.UUID;

/**
 * A proactive risk suggestion generated when a monitored saved route
 * is about to be affected by worsening weather. Delivered to the user
 * via SSE and stored in Redis with a 24-hour TTL.
 */
public record RiskSuggestion(
        UUID id,
        UUID tenantId,
        UUID routeId,
        String routeName,
        String severity,
        String suggestionText,
        int currentRisk,
        int alternativeRisk,
        String alternativeRoute,
        Instant createdAt,
        boolean acknowledged) {

    public RiskSuggestion {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (severity == null) {
            severity = "MODERATE";
        }
    }

    /** Returns a copy marked as acknowledged. */
    public RiskSuggestion withAcknowledged(boolean ack) {
        return new RiskSuggestion(id, tenantId, routeId, routeName, severity,
                suggestionText, currentRisk, alternativeRisk, alternativeRoute,
                createdAt, ack);
    }

    public static String severityForRisk(int riskScore) {
        if (riskScore >= 70) return "HIGH";
        if (riskScore >= 40) return "MODERATE";
        return "LOW";
    }
}
