package com.atmospath.platform.llm.orchestrator;

import java.util.List;

/**
 * The result of a route optimization solve: origin, destination, overall
 * metrics, and the ordered list of segments with per-segment risk data.
 */
public record SolutionResult(
        String origin,
        String destination,
        double totalDistanceMiles,
        int totalDurationMinutes,
        int overallRiskScore,
        List<RouteSegment> segments,
        List<String> activeAlerts) {

    public SolutionResult {
        if (segments == null) {
            segments = List.of();
        }
        if (activeAlerts == null) {
            activeAlerts = List.of();
        }
    }

    public String riskLevel() {
        if (overallRiskScore >= 70) return "High";
        if (overallRiskScore >= 40) return "Moderate";
        return "Low";
    }
}
