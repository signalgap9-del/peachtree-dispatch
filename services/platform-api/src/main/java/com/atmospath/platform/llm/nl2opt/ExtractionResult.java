package com.atmospath.platform.llm.nl2opt;

import java.util.List;
import java.util.Map;

import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.VrpConstraints;

/**
 * Outcome of a successful NL-to-constraint extraction. Bundles the validated
 * constraints with geocoding results and pipeline metadata (repair attempts,
 * estimated token usage).
 */
public record ExtractionResult(
        VrpConstraints constraints,
        Map<String, GeoPoint> geocodedStops,
        List<String> unresolvedPlaces,
        int repairAttempts,
        LlmUsage llmUsage) {

    /** Rough token accounting for the extraction call(s). */
    public record LlmUsage(int promptTokens, int completionTokens) {
        public int totalTokens() {
            return promptTokens + completionTokens;
        }
    }
}
