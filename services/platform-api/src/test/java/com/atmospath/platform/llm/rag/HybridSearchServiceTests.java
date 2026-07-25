package com.atmospath.platform.llm.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HybridSearchServiceTests {

    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final VectorSearchRepository vectorRepo = mock(VectorSearchRepository.class);

    private HybridSearchService service;

    private static final float[] FAKE_EMBEDDING = {0.1f, 0.2f, 0.3f};
    private static final UUID ROUTE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new HybridSearchService(embeddingService, vectorRepo);
        when(embeddingService.embed(anyString())).thenReturn(FAKE_EMBEDDING);
    }

    private SearchResult result(UUID id, double score) {
        return new SearchResult(id, "route_risk_observation", score, 50,
                "ice, wind", Instant.now(), ROUTE_ID, Map.of());
    }

    @Test
    void rrfMergeResultInBothListsGetsHigherScore() {
        UUID sharedId = UUID.randomUUID();
        UUID vectorOnlyId = UUID.randomUUID();
        UUID keywordOnlyId = UUID.randomUUID();

        List<SearchResult> vectorResults = List.of(
                result(sharedId, 0.95),
                result(vectorOnlyId, 0.80));
        List<SearchResult> keywordResults = List.of(
                result(sharedId, 0.70),
                result(keywordOnlyId, 0.60));

        List<SearchResult> merged = service.rrfMerge(vectorResults, keywordResults, 60);

        // sharedId appears in both lists so it should rank first
        assertThat(merged.get(0).observationId()).isEqualTo(sharedId);

        // RRF score for shared: 1/(60+1) + 1/(60+1) = 2/61
        double sharedScore = merged.get(0).score();
        assertThat(sharedScore).isCloseTo(2.0 / 61.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void rrfMergeCorrectOrdering() {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        UUID idC = UUID.randomUUID();

        // A is rank 1 in vector, rank 2 in keyword
        // B is rank 2 in vector, rank 1 in keyword
        // C is rank 3 in vector only
        List<SearchResult> vectorResults = List.of(
                result(idA, 0.9), result(idB, 0.8), result(idC, 0.7));
        List<SearchResult> keywordResults = List.of(
                result(idB, 0.9), result(idA, 0.8));

        List<SearchResult> merged = service.rrfMerge(vectorResults, keywordResults, 60);

        // A: 1/61 + 1/62, B: 1/62 + 1/61 => same score, tie broken by original score
        // A has higher score (0.9 vs 0.8) so A comes first
        assertThat(merged.get(0).observationId()).isEqualTo(idA);
        assertThat(merged.get(1).observationId()).isEqualTo(idB);
        assertThat(merged.get(2).observationId()).isEqualTo(idC);
    }

    @Test
    void emptyKeywordResultsVectorOnlyOrdering() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        List<SearchResult> vectorResults = List.of(
                result(id1, 0.95), result(id2, 0.80));

        List<SearchResult> merged = service.rrfMerge(vectorResults, List.of(), 60);

        assertThat(merged).hasSize(2);
        assertThat(merged.get(0).observationId()).isEqualTo(id1);
        assertThat(merged.get(1).observationId()).isEqualTo(id2);
    }

    @Test
    void emptyVectorResultsKeywordOnlyOrdering() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        List<SearchResult> keywordResults = List.of(
                result(id1, 0.90), result(id2, 0.70));

        List<SearchResult> merged = service.rrfMerge(List.of(), keywordResults, 60);

        assertThat(merged).hasSize(2);
        assertThat(merged.get(0).observationId()).isEqualTo(id1);
        assertThat(merged.get(1).observationId()).isEqualTo(id2);
    }

    @Test
    void rrfKParameterAffectsScores() {
        UUID id = UUID.randomUUID();
        List<SearchResult> single = List.of(result(id, 0.9));

        List<SearchResult> mergedK10 = service.rrfMerge(single, List.of(), 10);
        List<SearchResult> mergedK60 = service.rrfMerge(single, List.of(), 60);

        double scoreK10 = mergedK10.get(0).score();
        double scoreK60 = mergedK60.get(0).score();

        // Smaller k => higher score for rank 1
        assertThat(scoreK10).isGreaterThan(scoreK60);
        assertThat(scoreK10).isCloseTo(1.0 / 11.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(scoreK60).isCloseTo(1.0 / 61.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void searchAppliesFiltersWhenPresent() {
        SearchFilters filters = new SearchFilters("route-abc", null, null, 30, null, null);

        when(vectorRepo.searchWithFilters(any(), eq(filters), anyInt()))
                .thenReturn(List.of(result(UUID.randomUUID(), 0.9)));
        when(vectorRepo.searchByKeywordWithFilters(anyString(), eq(filters), anyInt()))
                .thenReturn(List.of());

        List<SearchResult> results = service.search("icy roads", filters, 5);

        verify(vectorRepo).searchWithFilters(any(), eq(filters), anyInt());
        verify(vectorRepo).searchByKeywordWithFilters(eq("icy roads"), eq(filters), anyInt());
        assertThat(results).isNotEmpty();
    }

    @Test
    void searchWithoutFiltersUsesUnfilteredQueries() {
        when(vectorRepo.searchByVector(any(), anyString(), anyInt()))
                .thenReturn(List.of(result(UUID.randomUUID(), 0.9)));
        when(vectorRepo.searchByKeyword(anyString(), anyString(), anyInt()))
                .thenReturn(List.of());

        service.search("wind gusts", null, 5);

        verify(vectorRepo).searchByVector(any(), eq("route_risk_observation"), anyInt());
        verify(vectorRepo).searchByKeyword(eq("wind gusts"), eq("route_risk_observation"), anyInt());
    }

    @Test
    void searchRespectsFinalLimit() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();

        when(vectorRepo.searchByVector(any(), anyString(), anyInt()))
                .thenReturn(List.of(result(id1, 0.9), result(id2, 0.8), result(id3, 0.7)));
        when(vectorRepo.searchByKeyword(anyString(), anyString(), anyInt()))
                .thenReturn(List.of());

        List<SearchResult> results = service.search("test", null, 2);

        assertThat(results).hasSize(2);
    }

    @Test
    void embeddingFailureFallsBackToKeywordOnly() {
        when(embeddingService.embed(anyString())).thenReturn(null);
        UUID id = UUID.randomUUID();
        when(vectorRepo.searchByKeyword(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(result(id, 0.85)));

        List<SearchResult> results = service.search("flood warning", null, 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).observationId()).isEqualTo(id);
    }
}
