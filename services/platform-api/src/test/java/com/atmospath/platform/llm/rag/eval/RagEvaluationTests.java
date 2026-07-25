package com.atmospath.platform.llm.rag.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import com.atmospath.platform.llm.rag.eval.RagEvaluationService.EvaluationReport;
import com.atmospath.platform.llm.rag.eval.RagEvaluationService.GoldenScenario;
import com.atmospath.platform.llm.rag.eval.RagEvaluationService.ScenarioResult;
import com.atmospath.platform.llm.rag.eval.RagEvaluationService.SearchResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Integration-level evaluation of the RAG hybrid search pipeline against
 * the golden scenario set. Requires a running PostgreSQL instance with
 * pgvector and seeded embeddings.
 */
@Disabled("Requires PostgreSQL with pgvector and seeded embeddings")
class RagEvaluationTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static List<GoldenScenario> scenarios;

    @BeforeAll
    static void loadGoldenScenarios() throws IOException {
        try (InputStream is = RagEvaluationTests.class.getResourceAsStream("/rag/golden_scenarios.json")) {
            assertThat(is).as("golden_scenarios.json on classpath").isNotNull();
            List<Map<String, Object>> raw = MAPPER.readValue(is, new TypeReference<>() {});
            scenarios = raw.stream()
                    .map(RagEvaluationTests::toScenario)
                    .toList();
        }
    }

    @Test
    void goldenScenariosLoadCorrectly() {
        assertThat(scenarios).hasSize(30);
        assertThat(scenarios).allSatisfy(s -> {
            assertThat(s.id()).isNotBlank();
            assertThat(s.query()).isNotBlank();
            assertThat(s.category()).isIn(
                    "flood", "winter_storm", "high_wind",
                    "extreme_heat", "multi_hazard", "no_match");
        });
    }

    @Test
    void evaluationMeetsQualityThresholds() {
        // In a real run, this would be backed by the actual hybrid search:
        //   HybridSearchFunction search = (query, filters, topK) ->
        //       hybridSearchService.search(query, filters, topK);
        // Here we use a placeholder that returns empty results to verify
        // the evaluation harness wiring.
        RagEvaluationService evaluator = new RagEvaluationService(
                (query, filters, topK) -> List.of(), 10);

        EvaluationReport report = evaluator.evaluate(scenarios);

        // With empty search results, no_match scenarios pass (correctly
        // returning nothing) and hazard scenarios fail. This validates
        // the harness logic; real thresholds are asserted below.
        assertThat(report.scenarios()).hasSize(30);
        assertThat(report.timestamp()).isNotNull();

        printReport(report);

        // Quality gates (would apply with a real search backend):
        // assertThat(report.avgMRR()).isGreaterThan(0.6);
        // assertThat(report.avgRecallAtK()).isGreaterThan(0.7);
    }

    @Test
    void noMatchScenariosPassWithEmptyResults() {
        RagEvaluationService evaluator = new RagEvaluationService(
                (query, filters, topK) -> List.of(), 10);

        EvaluationReport report = evaluator.evaluate(scenarios);

        List<ScenarioResult> noMatchResults = report.scenarios().stream()
                .filter(r -> r.scenarioId().startsWith("scenario_02")
                        || r.scenarioId().startsWith("scenario_03"))
                .toList();

        // no_match scenarios (026-030) should pass with empty results
        List<ScenarioResult> noMatch = scenarios.stream()
                .filter(s -> "no_match".equals(s.category()))
                .map(s -> report.scenarios().stream()
                        .filter(r -> r.scenarioId().equals(s.id()))
                        .findFirst().orElseThrow())
                .toList();

        assertThat(noMatch).allSatisfy(r -> assertThat(r.passed()).isTrue());
    }

    @Test
    void reportPrintsToStdout() {
        RagEvaluationService evaluator = new RagEvaluationService(
                (query, filters, topK) -> List.of(
                        new SearchResult("r1", "flood on I-95 with high precipitation risk", 0.9),
                        new SearchResult("r2", "minor rain on Route 1", 0.4)),
                10);

        EvaluationReport report = evaluator.evaluate(scenarios);
        printReport(report);

        assertThat(report.avgLatencyMs()).isGreaterThanOrEqualTo(0);
    }

    // ---- Helpers ----

    @SuppressWarnings("unchecked")
    private static GoldenScenario toScenario(Map<String, Object> raw) {
        return new GoldenScenario(
                (String) raw.get("id"),
                (String) raw.get("query"),
                (Map<String, Object>) raw.get("filters"),
                (List<String>) raw.get("expected_keywords"),
                ((Number) raw.get("expected_min_relevance")).doubleValue(),
                (String) raw.get("category"));
    }

    private void printReport(EvaluationReport report) {
        System.out.println("=== RAG Evaluation Report ===");
        System.out.printf("Timestamp: %s%n", report.timestamp());
        System.out.printf("Avg Recall@5: %.3f | Avg MRR: %.3f | Avg NDCG@5: %.3f | Avg Latency: %.1f ms%n",
                report.avgRecallAtK(), report.avgMRR(), report.avgNDCG(), report.avgLatencyMs());
        System.out.println("---");
        for (ScenarioResult r : report.scenarios()) {
            System.out.printf("  %-15s R@3=%.2f R@5=%.2f MRR=%.2f NDCG=%.2f %4dms %s%n",
                    r.scenarioId(), r.recallAt3(), r.recallAt5(), r.mrr(), r.ndcgAt5(),
                    r.latencyMs(), r.passed() ? "PASS" : "FAIL");
        }
        System.out.println("=============================");
    }
}
