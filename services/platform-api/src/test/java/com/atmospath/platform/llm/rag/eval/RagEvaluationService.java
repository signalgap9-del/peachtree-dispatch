package com.atmospath.platform.llm.rag.eval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Test utility that evaluates RAG hybrid-search quality against a golden
 * scenario set. Not a Spring bean; instantiate directly in tests with a
 * {@link HybridSearchFunction} and (optionally) a {@link TextGenerator}
 * for A/B comparisons.
 */
public class RagEvaluationService {

    // ---- Domain records ----

    public record GoldenScenario(
            String id,
            String query,
            Map<String, Object> filters,
            List<String> expectedKeywords,
            double expectedMinRelevance,
            String category) {}

    public record SearchResult(String id, String content, double score) {}

    public record ScenarioResult(
            String scenarioId,
            double recallAt3,
            double recallAt5,
            double mrr,
            double ndcgAt5,
            long latencyMs,
            boolean passed) {}

    public record EvaluationReport(
            List<ScenarioResult> scenarios,
            double avgRecallAtK,
            double avgMRR,
            double avgNDCG,
            double avgLatencyMs,
            Instant timestamp) {}

    public record ABComparisonResult(
            String query,
            String withoutRag,
            String withRag,
            double keywordCoverage,
            double specificityScore,
            boolean hasCitations) {}

    // ---- Functional interfaces ----

    @FunctionalInterface
    public interface HybridSearchFunction {
        List<SearchResult> search(String query, Map<String, Object> filters, int topK);
    }

    @FunctionalInterface
    public interface TextGenerator {
        String generate(String prompt);
    }

    private final HybridSearchFunction searchFunction;
    private final TextGenerator textGenerator;
    private final int topK;

    public RagEvaluationService(HybridSearchFunction searchFunction, TextGenerator textGenerator, int topK) {
        this.searchFunction = searchFunction;
        this.textGenerator = textGenerator;
        this.topK = topK;
    }

    public RagEvaluationService(HybridSearchFunction searchFunction, int topK) {
        this(searchFunction, null, topK);
    }

    // ---- Full evaluation ----

    /**
     * Runs every golden scenario through hybrid search, computes per-scenario
     * metrics, and aggregates them into a single report.
     */
    public EvaluationReport evaluate(List<GoldenScenario> scenarios) {
        List<ScenarioResult> results = new ArrayList<>();

        for (GoldenScenario scenario : scenarios) {
            long start = System.nanoTime();
            List<SearchResult> searchResults =
                    searchFunction.search(scenario.query(), scenario.filters(), topK);
            long latencyMs = (System.nanoTime() - start) / 1_000_000;

            double recallAt3 = computeRecallAtK(searchResults, scenario.expectedKeywords(), 3);
            double recallAt5 = computeRecallAtK(searchResults, scenario.expectedKeywords(), 5);
            double mrr = computeMRR(searchResults, scenario.expectedKeywords());
            double ndcgAt5 = computeNDCG(searchResults, scenario.expectedKeywords(), 5);

            boolean passed = isPassed(scenario, recallAt5, mrr);
            results.add(new ScenarioResult(
                    scenario.id(), recallAt3, recallAt5, mrr, ndcgAt5, latencyMs, passed));
        }

        double avgRecall = results.stream().mapToDouble(ScenarioResult::recallAt5).average().orElse(0);
        double avgMrr = results.stream().mapToDouble(ScenarioResult::mrr).average().orElse(0);
        double avgNdcg = results.stream().mapToDouble(ScenarioResult::ndcgAt5).average().orElse(0);
        double avgLatency = results.stream().mapToLong(ScenarioResult::latencyMs).average().orElse(0);

        return new EvaluationReport(results, avgRecall, avgMrr, avgNdcg, avgLatency, Instant.now());
    }

    // ---- Individual metrics ----

    /**
     * Fraction of expected keywords found (case-insensitive) in the content
     * of the top-k results. Returns 0.0 when there are no expected keywords.
     */
    public double computeRecallAtK(List<SearchResult> results, List<String> expectedKeywords, int k) {
        if (expectedKeywords == null || expectedKeywords.isEmpty()) {
            return 0.0;
        }
        String corpus = results.stream()
                .limit(k)
                .map(SearchResult::content)
                .reduce("", (a, b) -> a + " " + b)
                .toLowerCase(Locale.ROOT);

        long found = expectedKeywords.stream()
                .filter(kw -> corpus.contains(kw.toLowerCase(Locale.ROOT)))
                .count();
        return (double) found / expectedKeywords.size();
    }

    /**
     * Mean reciprocal rank: 1/rank of the first result whose content
     * contains at least one expected keyword. Returns 0.0 when no result
     * matches or there are no expected keywords.
     */
    public double computeMRR(List<SearchResult> results, List<String> expectedKeywords) {
        if (expectedKeywords == null || expectedKeywords.isEmpty()) {
            return 0.0;
        }
        List<String> lowered = expectedKeywords.stream()
                .map(kw -> kw.toLowerCase(Locale.ROOT))
                .toList();

        for (int i = 0; i < results.size(); i++) {
            String content = results.get(i).content().toLowerCase(Locale.ROOT);
            if (lowered.stream().anyMatch(content::contains)) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * Normalized discounted cumulative gain at k. Relevance of each result
     * is graded as the fraction of expected keywords it contains.
     * Returns 0.0 when there are no expected keywords or no results.
     */
    public double computeNDCG(List<SearchResult> results, List<String> expectedKeywords, int k) {
        if (expectedKeywords == null || expectedKeywords.isEmpty() || results.isEmpty()) {
            return 0.0;
        }
        List<String> lowered = expectedKeywords.stream()
                .map(kw -> kw.toLowerCase(Locale.ROOT))
                .toList();

        double dcg = 0.0;
        int limit = Math.min(k, results.size());
        for (int i = 0; i < limit; i++) {
            double relevance = gradedRelevance(results.get(i).content(), lowered);
            dcg += relevance / (Math.log(i + 2) / Math.log(2)); // log2(i+2), 1-indexed
        }

        // Ideal DCG: sort all result relevances descending, take top k
        List<Double> allRelevances = results.stream()
                .map(r -> gradedRelevance(r.content(), lowered))
                .sorted(Comparator.reverseOrder())
                .limit(k)
                .toList();

        double idcg = 0.0;
        for (int i = 0; i < allRelevances.size(); i++) {
            idcg += allRelevances.get(i) / (Math.log(i + 2) / Math.log(2));
        }

        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    // ---- A/B comparison ----

    /**
     * Compares LLM output with and without RAG context for the same query.
     * Requires a {@link TextGenerator}; throws if none was provided.
     */
    public ABComparisonResult compareWithAndWithoutRag(
            String query, List<String> contextKeywords, String ragContext) {
        if (textGenerator == null) {
            throw new IllegalStateException("TextGenerator required for A/B comparison");
        }

        String basePrompt = "Explain the weather risk for this query: " + query;
        String ragPrompt = basePrompt + "\n\nHistorical context:\n" + ragContext
                + "\n\nUse the historical context above to ground your explanation.";

        String withoutRag = textGenerator.generate(basePrompt);
        String withRag = textGenerator.generate(ragPrompt);

        double coverage = keywordCoverage(withRag, contextKeywords);
        double specificity = specificityScore(withRag, withoutRag);
        boolean citations = containsCitations(withRag);

        return new ABComparisonResult(query, withoutRag, withRag, coverage, specificity, citations);
    }

    // ---- Internals ----

    private boolean isPassed(GoldenScenario scenario, double recallAt5, double mrr) {
        if ("no_match".equals(scenario.category())) {
            return recallAt5 == 0.0 && mrr == 0.0;
        }
        return recallAt5 >= scenario.expectedMinRelevance();
    }

    private double gradedRelevance(String content, List<String> loweredKeywords) {
        String lower = content.toLowerCase(Locale.ROOT);
        long hits = loweredKeywords.stream().filter(lower::contains).count();
        return (double) hits / loweredKeywords.size();
    }

    private double keywordCoverage(String text, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return 0.0;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        long found = keywords.stream()
                .filter(kw -> lower.contains(kw.toLowerCase(Locale.ROOT)))
                .count();
        return (double) found / keywords.size();
    }

    /**
     * Rough specificity heuristic: ratio of numeric tokens in the RAG
     * output vs. the non-RAG output. More numbers = more grounded.
     */
    private double specificityScore(String withRag, String withoutRag) {
        long ragNumbers = countNumericTokens(withRag);
        long baseNumbers = countNumericTokens(withoutRag);
        if (ragNumbers == 0) {
            return 0.0;
        }
        return baseNumbers == 0 ? 1.0 : Math.min(1.0, (double) ragNumbers / (baseNumbers * 2));
    }

    private boolean containsCitations(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("according to") || lower.contains("historical data")
                || lower.contains("recorded") || lower.contains("observed on")
                || lower.contains("data shows");
    }

    private long countNumericTokens(String text) {
        return java.util.Arrays.stream(text.split("\\s+"))
                .filter(t -> t.matches(".*\\d.*"))
                .count();
    }
}
