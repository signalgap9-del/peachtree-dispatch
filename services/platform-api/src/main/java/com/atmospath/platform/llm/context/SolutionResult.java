package com.atmospath.platform.llm.context;

import java.util.List;
import java.util.Map;

/**
 * Lightweight snapshot of a solver result stored in conversation context.
 * Captures the essential facts a follow-up turn might reference ("the
 * lower-risk option", "42 miles") without coupling to the full solver model.
 */
public record SolutionResult(
        String routeId,
        String label,
        double distanceMiles,
        double durationMinutes,
        String riskLevel,
        List<String> waypoints,
        Map<String, Object> metadata) {

    public SolutionResult {
        waypoints = waypoints != null ? List.copyOf(waypoints) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
