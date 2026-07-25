package com.atmospath.platform.llm.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.atmospath.platform.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CrossEncoderRerankerTests {

    private final LlmClient llmClient = mock(LlmClient.class);
    private CrossEncoderReranker reranker;

    private static final UUID ID_A = UUID.randomUUID();
    private static final UUID ID_B = UUID.randomUUID();
    private static final UUID ID_C = UUID.randomUUID();
    private static final UUID ROUTE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        reranker = new CrossEncoderReranker(llmClient);
    }

    private SearchResult result(UUID id, int riskScore) {
        return new SearchResult(id, "route_risk_observation", 0.8, riskScore,
                "ice, wind", Instant.now(), ROUTE_ID, Map.of());
    }

    @Test
    void llmReturnsOrderedIdsCorrectReranking() {
        List<SearchResult> candidates = List.of(
                result(ID_A, 30), result(ID_B, 60), result(ID_C, 90));

        // LLM says C is most relevant, then A, then B
        when(llmClient.complete(anyList()))
                .thenReturn(ID_C + ", " + ID_A + ", " + ID_B);

        List<SearchResult> reranked = reranker.rerank("severe weather", candidates, 3);

        assertThat(reranked).hasSize(3);
        assertThat(reranked.get(0).observationId()).isEqualTo(ID_C);
        assertThat(reranked.get(1).observationId()).isEqualTo(ID_A);
        assertThat(reranked.get(2).observationId()).isEqualTo(ID_B);
    }

    @Test
    void llmReturnsPartialIdsMissingAppendedAtEnd() {
        List<SearchResult> candidates = List.of(
                result(ID_A, 30), result(ID_B, 60), result(ID_C, 90));

        // LLM only mentions C and A; B should be appended at end
        when(llmClient.complete(anyList()))
                .thenReturn(ID_C + ", " + ID_A);

        List<SearchResult> reranked = reranker.rerank("flood risk", candidates, 3);

        assertThat(reranked).hasSize(3);
        assertThat(reranked.get(0).observationId()).isEqualTo(ID_C);
        assertThat(reranked.get(1).observationId()).isEqualTo(ID_A);
        assertThat(reranked.get(2).observationId()).isEqualTo(ID_B);
    }

    @Test
    void llmFailureFallsBackToOriginalOrder() {
        List<SearchResult> candidates = List.of(
                result(ID_A, 30), result(ID_B, 60), result(ID_C, 90));

        when(llmClient.complete(anyList()))
                .thenThrow(new RuntimeException("LLM timeout"));

        List<SearchResult> reranked = reranker.rerank("query", candidates, 3);

        assertThat(reranked).hasSize(3);
        assertThat(reranked.get(0).observationId()).isEqualTo(ID_A);
        assertThat(reranked.get(1).observationId()).isEqualTo(ID_B);
        assertThat(reranked.get(2).observationId()).isEqualTo(ID_C);
    }

    @Test
    void topKLimitsOutput() {
        List<SearchResult> candidates = List.of(
                result(ID_A, 30), result(ID_B, 60), result(ID_C, 90));

        when(llmClient.complete(anyList()))
                .thenReturn(ID_C + ", " + ID_B + ", " + ID_A);

        List<SearchResult> reranked = reranker.rerank("query", candidates, 2);

        assertThat(reranked).hasSize(2);
        assertThat(reranked.get(0).observationId()).isEqualTo(ID_C);
        assertThat(reranked.get(1).observationId()).isEqualTo(ID_B);
    }

    @Test
    void emptyCandidatesReturnsEmpty() {
        List<SearchResult> reranked = reranker.rerank("query", List.of(), 5);
        assertThat(reranked).isEmpty();
    }

    @Test
    void parseIdListToleratesFormattingArtifacts() {
        String response = "1. " + ID_A + "\n2. " + ID_B + "\n";
        List<UUID> parsed = reranker.parseIdList(response);

        assertThat(parsed).containsExactly(ID_A, ID_B);
    }
}
