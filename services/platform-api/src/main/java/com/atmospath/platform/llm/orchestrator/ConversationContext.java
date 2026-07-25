package com.atmospath.platform.llm.orchestrator;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Snapshot of a conversational session: the last intent, extracted
 * constraints, solver result, and alternatives. Fully managed by
 * {@code ConversationContextService} (Phase 2D); the orchestrator
 * reads and writes through that service.
 */
public record ConversationContext(
        String sessionId,
        String lastIntent,
        Map<String, Object> extractedConstraints,
        SolutionResult lastSolution,
        List<RouteAlternative> lastAlternatives,
        String lastUserMessage,
        Instant updatedAt) {

    public ConversationContext {
        if (extractedConstraints == null) {
            extractedConstraints = Map.of();
        }
        if (lastAlternatives == null) {
            lastAlternatives = List.of();
        }
    }

    public static ConversationContext empty(String sessionId) {
        return new ConversationContext(sessionId, null, Map.of(), null, List.of(), null, Instant.now());
    }

    public boolean hasPreviousSolution() {
        return lastSolution != null;
    }

    public boolean hasAlternatives() {
        return lastAlternatives != null && !lastAlternatives.isEmpty();
    }
}
