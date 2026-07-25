package com.atmospath.platform.llm.rag.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import com.atmospath.platform.llm.rag.eval.RagEvaluationService.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the RAG evaluation metrics. No database or external
 * services required; all inputs are constructed in-memory.
 */
class EvaluationMetricsTests {

    private RagEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new RagEvaluationService((q, f, k) -> List.of(), 10);
    }

    // ---- recall@k ----

    @Test
    void recallAtKFindsAllKeywordsInTopK() {
        List<SearchResult> results = List.of(
                new SearchResult("1", "Major flood on I-95 with heavy precipitation", 0.95),
                new SearchResult("2", "Risk score elevated due to flooding", 0.80));
        List<String> keywords = List.of("flood", "I-95", "precipitation", "risk");

        double recall = service.computeRecallAtK(results, keywords, 5);

        assertThat(recall).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void recallAtKPartialMatch() {
        List<SearchResult> results = List.of(
                new SearchResult("1", "Flood warning issued for the region", 0.90),
                new SearchResult("2", "Traffic delay on Route 1", 0.50));
        List<String> keywords = List.of("flood", "I-95", "precipitation", "risk");

        double recall = service.computeRecallAtK(results, keywords, 5);

        // Only "flood" found out of 4 keywords
        assertThat(recall).isCloseTo(0.25, within(1e-9));
    }

    @Test
    void recallAtKRespectsKLimit() {
        List<SearchResult> results = List.of(
                new SearchResult("1", "Unrelated content about traffic", 0.90),
                new SearchResult("2", "Also unrelated weather chatter", 0.80),
                new SearchResult("3", "Flood on I-95 with precipitation and risk", 0.70));
        List<String> keywords = List.of("flood", "I-95", "precipitation", "risk");

        // k=2 should miss the relevant result at position 3
        assertThat(service.computeRecallAtK(results, keywords, 2)).isCloseTo(0.0, within(1e-9));
        // k=3 should find everything
        assertThat(service.computeRecallAtK(results, keywords, 3)).isCloseTo(1.0, within(1e-9));
    }

    // ---- MRR ----

    @Test
    void mrrFirstRelevantAtRank1() {
        List<SearchResult> results = List.of(
                new SearchResult("1", "Flood warning on I-95", 0.95),
                new SearchResult("2", "Unrelated content", 0.50));
        List<String> keywords = List.of("flood");

        assertThat(service.computeMRR(results, keywords)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void mrrFirstRelevantAtRank3() {
        List<SearchResult> results = List.of(
                new SearchResult("1", "Unrelated traffic report", 0.90),
                new SearchResult("2", "Another unrelated result", 0.80),
                new SearchResult("3", "Flood advisory for the area", 0.70));
        List<String> keywords = List.of("flood");

        assertThat(service.computeMRR(results, keywords)).isCloseTo(1.0 / 3, within(1e-9));
    }

    @Test
    void mrrNoRelevantResults() {
        List<SearchResult> results = List.of(
                new SearchResult("1", "Traffic report", 0.90),
                new SearchResult("2", "Weather forecast", 0.80));
        List<String> keywords = List.of("flood", "hurricane");

        assertThat(service.computeMRR(results, keywords)).isCloseTo(0.0, within(1e-9));
    }

    // ---- NDCG ----

    @Test
    void ndcgPerfectRanking() {
        // All keywords in the first result, nothing in the second
        List<SearchResult> results = List.of(
                new SearchResult("1", "flood I-95 precipitation risk", 0.95),
                new SearchResult("2", "unrelated content", 0.50));
        List<String> keywords = List.of("flood", "I-95", "precipitation", "risk");

        double ndcg = service.computeNDCG(results, keywords, 5);

        assertThat(ndcg).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void ndcgGradedRelevance() {
        // First result has 2/4 keywords, second has 4/4
        List<SearchResult> results = List.of(
                new SearchResult("1", "flood warning with high precipitation", 0.90),
                new SearchResult("2", "flood on I-95 with precipitation and risk", 0.85));
        List<String> keywords = List.of("flood", "I-95", "precipitation", "risk");

        double ndcg = service.computeNDCG(results, keywords, 5);

        // Not perfect because the best result is at rank 2, not rank 1
        assertThat(ndcg).isGreaterThan(0.5);
        assertThat(ndcg).isLessThan(1.0);
    }

    // ---- Edge cases ----

    @Test
    void emptyResultsYieldZeroMetrics() {
        List<SearchResult> empty = List.of();
        List<String> keywords = List.of("flood", "I-95");

        assertThat(service.computeRecallAtK(empty, keywords, 5)).isCloseTo(0.0, within(1e-9));
        assertThat(service.computeMRR(empty, keywords)).isCloseTo(0.0, within(1e-9));
        assertThat(service.computeNDCG(empty, keywords, 5)).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void emptyKeywordsYieldZeroMetrics() {
        List<SearchResult> results = List.of(
                new SearchResult("1", "Some content", 0.90));
        List<String> noKeywords = List.of();

        assertThat(service.computeRecallAtK(results, noKeywords, 5)).isCloseTo(0.0, within(1e-9));
        assertThat(service.computeMRR(results, noKeywords)).isCloseTo(0.0, within(1e-9));
        assertThat(service.computeNDCG(results, noKeywords, 5)).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void keywordMatchingIsCaseInsensitive() {
        List<SearchResult> results = List.of(
                new SearchResult("1", "FLOOD WARNING on i-95 SOUTH", 0.90));
        List<String> keywords = List.of("flood", "I-95");

        assertThat(service.computeRecallAtK(results, keywords, 5)).isCloseTo(1.0, within(1e-9));
        assertThat(service.computeMRR(results, keywords)).isCloseTo(1.0, within(1e-9));
    }
}
