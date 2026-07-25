package com.atmospath.platform.llm.intent;

import java.util.Locale;

/** Routing intents understood by the conversational orchestration layer. */
public enum Intent {

    ROUTE_PLAN,
    MODIFY,
    COMPARE,
    FLEET_OPTIMIZE,
    EXPLAIN,
    UNKNOWN;

    /**
     * Parses raw LLM output into an {@link Intent}. Tolerates surrounding
     * whitespace, capitalization, and spaces instead of underscores; anything
     * unrecognized maps to {@link #UNKNOWN} so callers can fall back safely.
     */
    public static Intent fromLlmOutput(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        String normalized = raw.strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "_")
                .replaceAll("[^a-z_]", "");
        return switch (normalized) {
            case "route_plan" -> ROUTE_PLAN;
            case "modify" -> MODIFY;
            case "compare" -> COMPARE;
            case "fleet_optimize" -> FLEET_OPTIMIZE;
            case "explain" -> EXPLAIN;
            default -> UNKNOWN;
        };
    }
}
