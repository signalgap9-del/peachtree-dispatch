package com.atmospath.platform.llm.rag;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST endpoints for the RAG pipeline: standalone search, prompt
 * augmentation, and health/status reporting.
 */
@RestController
@RequestMapping("/api/v1/rag")
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final RagService ragService;
    private final ObjectProvider<HybridSearchService> searchProvider;

    public RagController(RagService ragService,
                         ObjectProvider<HybridSearchService> searchProvider) {
        this.ragService = ragService;
        this.searchProvider = searchProvider;
    }

    // ---- DTOs ----

    record SearchRequest(String query, FiltersDto filters, Integer limit) {}

    record FiltersDto(String routeId, Instant timeFrom, Instant timeTo,
                      Integer minRiskScore, String severity, String tenantId) {
        SearchFilters toFilters() {
            return new SearchFilters(routeId, timeFrom, timeTo, minRiskScore, severity, tenantId);
        }
    }

    record AugmentRequest(String query, FiltersDto filters) {}

    record SearchResponseDto(List<SourceReference> results, long latencyMs, int totalFound) {}

    record AugmentResponseDto(String context, List<SourceReference> sources, long searchLatencyMs) {}

    // ---- Endpoints ----

    /**
     * Standalone hybrid search endpoint. Returns ranked source references
     * without assembling a prompt context string.
     */
    @PostMapping("/search")
    public SearchResponseDto search(@RequestBody SearchRequest body) {
        if (body.query() == null || body.query().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query must not be empty");
        }

        SearchFilters filters = body.filters() != null ? body.filters().toFilters() : SearchFilters.none();
        RagResult result = ragService.augment(body.query(), filters);

        int limit = body.limit() != null ? body.limit() : result.sources().size();
        List<SourceReference> limited = result.sources().stream().limit(limit).toList();

        return new SearchResponseDto(limited, result.searchLatencyMs(), result.resultCount());
    }

    /**
     * Returns RAG context for a query: the assembled context string and
     * source references ready for prompt injection.
     */
    @PostMapping("/augment")
    public AugmentResponseDto augment(@RequestBody AugmentRequest body) {
        if (body.query() == null || body.query().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query must not be empty");
        }

        SearchFilters filters = body.filters() != null ? body.filters().toFilters() : SearchFilters.none();
        RagResult result = ragService.augment(body.query(), filters);

        return new AugmentResponseDto(result.context(), result.sources(), result.searchLatencyMs());
    }

    /**
     * Health endpoint reporting embedding/search service status and
     * index statistics.
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        HybridSearchService search = searchProvider.getIfAvailable();
        boolean available = search != null && search.isAvailable();
        long indexCount = available ? search.indexCount() : -1;

        return Map.of(
                "status", available ? "UP" : "DOWN",
                "searchAvailable", available,
                "indexCount", indexCount);
    }
}
