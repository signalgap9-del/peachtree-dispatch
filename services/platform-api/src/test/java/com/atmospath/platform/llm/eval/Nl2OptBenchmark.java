package com.atmospath.platform.llm.eval;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Evaluation benchmark for NL2Opt constraint extraction accuracy.
 * Not a Spring bean; instantiate directly in tests with extraction
 * function implementations for each baseline variant.
 *
 * <p>Baselines compared:
 * <ul>
 *   <li>(a) LLM with few-shot examples (production implementation)</li>
 *   <li>(b) LLM zero-shot (no examples in prompt)</li>
 *   <li>(c) Keyword-based extraction (no LLM)</li>
 * </ul>
 *
 * <p>Metrics: constraint precision, recall, F1, geocoding accuracy,
 * and repair-loop invocation rate.
 */
public class Nl2OptBenchmark {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // ---- Domain records ----

    public record Scenario(
            String id,
            String input,
            Map<String, Object> expectedConstraints,
            List<String> expectedPlaces,
            String category) {}

    public record ExtractionOutput(
            Map<String, Object> constraints,
            List<String> resolvedPlaces,
            int repairAttempts) {}

    public record ScenarioResult(
            String scenarioId,
            String category,
            double precision,
            double recall,
            double f1,
            double geocodingAccuracy,
            boolean repairNeeded,
            long latencyMs) {}

    public record BenchmarkReport(
            String baseline,
            List<ScenarioResult> scenarios,
            double avgPrecision,
            double avgRecall,
            double avgF1,
            double avgGeocodingAccuracy,
            double repairRate,
            Instant timestamp) {}

    // ---- Functional interfaces ----

    @FunctionalInterface
    public interface ExtractionFunction {
        ExtractionOutput extract(String input);
    }

    // ---- Scenario loading ----

    /**
     * Loads scenarios from the classpath resource
     * {@code eval/nl2opt_scenarios.json}.
     */
    public static List<Scenario> loadScenarios() {
        try (InputStream is = Nl2OptBenchmark.class
                .getResourceAsStream("/eval/nl2opt_scenarios.json")) {
            if (is == null) {
                throw new IllegalStateException("eval/nl2opt_scenarios.json not found on classpath");
            }
            return MAPPER.readValue(is, new TypeReference<List<Scenario>>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load NL2Opt scenarios", ex);
        }
    }

    // ---- Benchmark execution ----

    /**
     * Runs all scenarios through the given extraction function and
     * computes aggregate metrics.
     */
    public BenchmarkReport run(String baselineName, ExtractionFunction extractor) {
        List<Scenario> scenarios = loadScenarios();
        return run(baselineName, extractor, scenarios);
    }

    /**
     * Runs a provided scenario list through the given extraction function.
     */
    public BenchmarkReport run(String baselineName, ExtractionFunction extractor,
                               List<Scenario> scenarios) {
        List<ScenarioResult> results = new ArrayList<>();

        for (Scenario scenario : scenarios) {
            long start = System.nanoTime();
            ExtractionOutput output = extractor.extract(scenario.input());
            long latencyMs = (System.nanoTime() - start) / 1_000_000;

            double precision = computePrecision(output.constraints(), scenario.expectedConstraints());
            double recall = computeRecall(output.constraints(), scenario.expectedConstraints());
            double f1 = harmonicMean(precision, recall);
            double geoAccuracy = computeGeocodingAccuracy(
                    output.resolvedPlaces(), scenario.expectedPlaces());

            results.add(new ScenarioResult(
                    scenario.id(), scenario.category(),
                    precision, recall, f1, geoAccuracy,
                    output.repairAttempts() > 0, latencyMs));
        }

        double avgPrecision = results.stream().mapToDouble(ScenarioResult::precision).average().orElse(0);
        double avgRecall = results.stream().mapToDouble(ScenarioResult::recall).average().orElse(0);
        double avgF1 = results.stream().mapToDouble(ScenarioResult::f1).average().orElse(0);
        double avgGeo = results.stream().mapToDouble(ScenarioResult::geocodingAccuracy).average().orElse(0);
        double repairRate = (double) results.stream().filter(ScenarioResult::repairNeeded).count()
                / Math.max(1, results.size());

        return new BenchmarkReport(baselineName, results,
                avgPrecision, avgRecall, avgF1, avgGeo, repairRate, Instant.now());
    }

    // ---- Metrics ----

    /**
     * Constraint precision: fraction of extracted constraint keys that
     * match expected values.
     */
    public double computePrecision(Map<String, Object> extracted, Map<String, Object> expected) {
        if (extracted == null || extracted.isEmpty()) {
            return 0.0;
        }
        Set<String> extractedKeys = flattenKeys(extracted);
        Set<String> expectedKeys = flattenKeys(expected);
        if (extractedKeys.isEmpty()) {
            return 0.0;
        }
        long correct = extractedKeys.stream()
                .filter(k -> expectedKeys.contains(k) && valuesMatch(extracted, expected, k))
                .count();
        return (double) correct / extractedKeys.size();
    }

    /**
     * Constraint recall: fraction of expected constraint keys that
     * were correctly extracted.
     */
    public double computeRecall(Map<String, Object> extracted, Map<String, Object> expected) {
        if (expected == null || expected.isEmpty()) {
            return 1.0;
        }
        Set<String> expectedKeys = flattenKeys(expected);
        if (expectedKeys.isEmpty()) {
            return 1.0;
        }
        Set<String> extractedKeys = flattenKeys(extracted != null ? extracted : Map.of());
        long found = expectedKeys.stream()
                .filter(k -> extractedKeys.contains(k) && valuesMatch(extracted, expected, k))
                .count();
        return (double) found / expectedKeys.size();
    }

    /**
     * Geocoding accuracy: fraction of expected places that were
     * successfully resolved.
     */
    public double computeGeocodingAccuracy(List<String> resolved, List<String> expected) {
        if (expected == null || expected.isEmpty()) {
            return 1.0;
        }
        Set<String> resolvedLower = resolved == null ? Set.of()
                : resolved.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        long found = expected.stream()
                .filter(p -> resolvedLower.contains(p.toLowerCase(Locale.ROOT)))
                .count();
        return (double) found / expected.size();
    }

    /** Harmonic mean of precision and recall; 0 if either is 0. */
    public static double harmonicMean(double precision, double recall) {
        if (precision + recall == 0) {
            return 0.0;
        }
        return 2.0 * precision * recall / (precision + recall);
    }

    // ---- Category breakdown ----

    /** Groups scenario results by category for per-category analysis. */
    public Map<String, List<ScenarioResult>> byCategory(BenchmarkReport report) {
        return report.scenarios().stream()
                .collect(Collectors.groupingBy(ScenarioResult::category));
    }

    // ---- Internals ----

    @SuppressWarnings("unchecked")
    private Set<String> flattenKeys(Map<String, Object> map) {
        Set<String> keys = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            keys.add(entry.getKey());
            if (entry.getValue() instanceof Map) {
                for (String sub : flattenKeys((Map<String, Object>) entry.getValue())) {
                    keys.add(entry.getKey() + "." + sub);
                }
            }
        }
        return keys;
    }

    @SuppressWarnings("unchecked")
    private boolean valuesMatch(Map<String, Object> extracted, Map<String, Object> expected, String key) {
        String[] parts = key.split("\\.", 2);
        Object extVal = extracted.get(parts[0]);
        Object expVal = expected.get(parts[0]);
        if (parts.length == 1) {
            return normalizeValue(extVal).equals(normalizeValue(expVal));
        }
        if (extVal instanceof Map && expVal instanceof Map) {
            return valuesMatch((Map<String, Object>) extVal, (Map<String, Object>) expVal, parts[1]);
        }
        return false;
    }

    private String normalizeValue(Object val) {
        if (val == null) {
            return "null";
        }
        return String.valueOf(val).toLowerCase(Locale.ROOT).strip();
    }
}
