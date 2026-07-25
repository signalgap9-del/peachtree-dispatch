package com.atmospath.platform.llm.orchestrator;

import java.util.List;

/**
 * One route option in a multi-alternative comparison. Carries enough
 * data for the comparison report prompt to weigh trade-offs.
 */
public record RouteAlternative(
        String label,
        String description,
        double distanceMiles,
        int durationMinutes,
        int riskScore,
        List<String> alerts,
        List<RouteSegment> segments) {

    public RouteAlternative {
        if (alerts == null) {
            alerts = List.of();
        }
        if (segments == null) {
            segments = List.of();
        }
    }

    public String riskLevel() {
        if (riskScore >= 70) return "High";
        if (riskScore >= 40) return "Moderate";
        return "Low";
    }
}
