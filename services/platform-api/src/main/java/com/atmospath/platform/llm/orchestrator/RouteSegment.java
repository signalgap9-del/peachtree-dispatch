package com.atmospath.platform.llm.orchestrator;

import java.util.List;

/**
 * A single segment of a computed route: the road, its risk, and any
 * active weather or traffic alerts affecting it.
 */
public record RouteSegment(
        String name,
        double distanceMiles,
        int durationMinutes,
        int riskScore,
        List<String> alerts) {

    public RouteSegment {
        if (alerts == null) {
            alerts = List.of();
        }
    }

    public boolean hasAlerts() {
        return !alerts.isEmpty();
    }
}
