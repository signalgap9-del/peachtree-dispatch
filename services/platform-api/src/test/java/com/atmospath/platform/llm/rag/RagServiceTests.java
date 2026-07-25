package com.atmospath.platform.llm.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.atmospath.platform.llm.LiteLlmClient;
import com.atmospath.platform.llm.Message;
import com.atmospath.platform.llm.prompt.PromptTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class RagServiceTests {

    private HybridSearchService searchService;
    private CrossEncoderReranker reranker;
    private LiteLlmClient llmClient;
    private PromptTemplateService templates;
    private RagService ragService;

    private static final UUID OBS_ID_1 = UUID.fromString("abc12345-0000-0000-0000-000000000001");
    private static final UUID OBS_ID_2 = UUID.fromString("def67890-0000-0000-0000-000000000002");
    private static final UUID ROUTE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Instant OBS_TIME = Instant.parse("2026-03-15T10:00:00Z");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        searchService = mock(HybridSearchService.class);
        reranker = mock(CrossEncoderReranker.class);
        llmClient = mock(LiteLlmClient.class);
        templates = new PromptTemplateService();

        ObjectProvider<HybridSearchService> searchProvider = mock(ObjectProvider.class);
        ObjectProvider<CrossEncoderReranker> rerankerProvider = mock(ObjectProvider.class);
        ObjectProvider<LiteLlmClient> llmProvider = mock(ObjectProvider.class);

        when(searchProvider.getIfAvailable()).thenReturn(searchService);
        when(rerankerProvider.getIfAvailable()).thenReturn(reranker);
        when(llmProvider.getIfAvailable()).thenReturn(null); // no LLM for expansion

        ragService = new RagService(searchProvider, rerankerProvider, llmProvider, templates);
    }

    private static SearchResult result(UUID id, Instant time, int risk, double score, String factors) {
        return new SearchResult(id, "route_risk_observation", score, risk, factors, time, ROUTE_ID, Map.of());
    }

    @Test
    void augmentReturnsContextAndSources() {
        var results = List.of(
                result(OBS_ID_1, OBS_TIME, 85, 0.92, "flood warning, precipitation 90%"),
                result(OBS_ID_2, OBS_TIME.plusSeconds(3600), 72, 0.85, "wind 35mph"));

        when(searchService.isAvailable()).thenReturn(true);
        when(searchService.search(anyString(), any(), anyInt())).thenReturn(results);
        when(reranker.rerank(anyString(), any(), anyInt())).thenReturn(results);

        RagResult ragResult = ragService.augment("flood risk I-95", SearchFilters.none());

        assertThat(ragResult.resultCount()).isEqualTo(2);
        assertThat(ragResult.context()).contains("[Past observation 1]");
        assertThat(ragResult.context()).contains("risk 85/100");
        assertThat(ragResult.context()).contains("flood warning");
        assertThat(ragResult.sources()).hasSize(2);
        assertThat(ragResult.sources().get(0).observationId()).isEqualTo(OBS_ID_1);
        assertThat(ragResult.searchLatencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void augmentWithEmptySearchReturnsEmptyResult() {
        when(searchService.isAvailable()).thenReturn(true);
        when(searchService.search(anyString(), any(), anyInt())).thenReturn(List.of());

        RagResult ragResult = ragService.augment("unknown route xyz", SearchFilters.none());

        assertThat(ragResult.resultCount()).isZero();
        assertThat(ragResult.context()).isEqualTo("No relevant historical data found.");
        assertThat(ragResult.sources()).isEmpty();
    }

    @Test
    void augmentWithUnavailableSearchReturnsEmpty() {
        when(searchService.isAvailable()).thenReturn(false);

        RagResult ragResult = ragService.augment("flood risk", SearchFilters.none());

        assertThat(ragResult.resultCount()).isZero();
        assertThat(ragResult.context()).isEqualTo("No relevant historical data found.");
    }

    @Test
    void synonymExpansionAddsRelatedTerms() {
        String expanded = ragService.synonymExpand("flood risk I-95");

        assertThat(expanded).contains("flood risk I-95");
        assertThat(expanded).contains("flooding");
        assertThat(expanded).contains("water on road");
    }

    @Test
    void synonymExpansionWithNoMatchReturnsOriginal() {
        String expanded = ragService.synonymExpand("route optimization");

        assertThat(expanded).isEqualTo("route optimization");
    }

    @Test
    void contextAssemblyFormatsObservations() {
        var results = List.of(
                result(OBS_ID_1, OBS_TIME, 85, 0.92, "flood warning, precipitation 90%, wind 35mph"));

        String context = ragService.assembleContext(results);

        assertThat(context).contains("[Past observation 1] 2026-03-15");
        assertThat(context).contains("risk 85/100");
        assertThat(context).contains("factors: flood warning, precipitation 90%, wind 35mph");
    }

    @Test
    void contextAssemblyWithEmptyListReturnsNoData() {
        String context = ragService.assembleContext(List.of());

        assertThat(context).isEqualTo("No relevant historical data found.");
    }

    @Test
    void sourcesCorrectlyMappedFromSearchResults() {
        var results = List.of(
                result(OBS_ID_1, OBS_TIME, 85, 0.92, "flood warning"));

        when(searchService.isAvailable()).thenReturn(true);
        when(searchService.search(anyString(), any(), anyInt())).thenReturn(results);
        when(reranker.rerank(anyString(), any(), anyInt())).thenReturn(results);

        RagResult ragResult = ragService.augment("flood I-95", SearchFilters.none());

        SourceReference source = ragResult.sources().get(0);
        assertThat(source.observationId()).isEqualTo(OBS_ID_1);
        assertThat(source.tableName()).isEqualTo("route_risk_observation");
        assertThat(source.observationTime()).isEqualTo(OBS_TIME);
        assertThat(source.routeId()).isEqualTo(ROUTE_ID);
        assertThat(source.riskScore()).isEqualTo(85);
        assertThat(source.relevanceScore()).isEqualTo(0.92);
    }

    @Test
    void buildRagPromptAugmentsSystemMessage() {
        var results = List.of(
                result(OBS_ID_1, OBS_TIME, 85, 0.92, "flood warning"));

        when(searchService.isAvailable()).thenReturn(true);
        when(searchService.search(anyString(), any(), anyInt())).thenReturn(results);
        when(reranker.rerank(anyString(), any(), anyInt())).thenReturn(results);

        RagResult ragResult = ragService.augment("flood I-95", SearchFilters.none());

        Map<String, String> vars = Map.of(
                "origin", "Miami",
                "destination", "Jacksonville",
                "score", "85/100 (High)",
                "segments", "1. I-95 North: 120 mi",
                "alerts", "Flood warning");

        List<Message> messages = ragService.buildRagPrompt("route_explanation", vars, ragResult);

        assertThat(messages).hasSizeGreaterThanOrEqualTo(2);
        assertThat(messages.get(0).content()).contains("historical route risk observations");
    }

    @Test
    void buildRagPromptWithEmptyResultReturnsBaseMessages() {
        RagResult emptyResult = RagResult.empty(0);

        Map<String, String> vars = Map.of(
                "origin", "Miami",
                "destination", "Jacksonville",
                "score", "50/100 (Moderate)",
                "segments", "1. I-95: 120 mi",
                "alerts", "None");

        List<Message> messages = ragService.buildRagPrompt("route_explanation", vars, emptyResult);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).content()).doesNotContain("historical route risk observations");
    }
}
