package com.atmospath.platform.llm.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Hybrid search combining vector similarity and keyword (BM25) matching
 * with Reciprocal Rank Fusion (RRF). Uses {@link EmbeddingService} for
 * query embedding and {@link VectorSearchRepository} for both vector and
 * keyword retrieval against pgvector-enabled tables.
 */
@Service
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);
    private static final String DEFAULT_TABLE = "route_risk_observation";
    private static final int DEFAULT_RRF_K = 60;

    private final EmbeddingService embeddingService;
    private final VectorSearchRepository vectorRepo;

    public HybridSearchService(EmbeddingService embeddingService,
                               VectorSearchRepository vectorRepo) {
        this.embeddingService = embeddingService;
        this.vectorRepo = vectorRepo;
    }

    /**
     * Executes a hybrid search: embeds the query, runs vector and keyword
     * searches in parallel, and merges results via RRF.
     *
     * @param query   the search query
     * @param filters optional scope filters (route, time range, min risk)
     * @param limit   maximum number of results to return
     * @return search results ordered by RRF score descending
     */
    public List<SearchResult> search(String query, SearchFilters filters, int limit) {
        List<SearchResult> vectorResults = List.of();
        List<SearchResult> keywordResults = List.of();

        // Vector search (requires embedding)
        float[] embedding = embeddingService.embed(query);
        if (embedding != null) {
            if (filters != null) {
                vectorResults = vectorRepo.searchWithFilters(embedding, filters, limit * 2);
            } else {
                vectorResults = vectorRepo.searchByVector(embedding, DEFAULT_TABLE, limit * 2);
            }
        } else {
            log.debug("Embedding failed for query; falling back to keyword-only search");
        }

        // Keyword search (always attempted)
        if (filters != null) {
            keywordResults = vectorRepo.searchByKeywordWithFilters(query, filters, limit * 2);
        } else {
            keywordResults = vectorRepo.searchByKeyword(query, DEFAULT_TABLE, limit * 2);
        }

        // RRF merge
        List<SearchResult> merged = rrfMerge(vectorResults, keywordResults, DEFAULT_RRF_K);

        return merged.stream().limit(limit).toList();
    }

    /**
     * Reciprocal Rank Fusion: merges two ranked lists into one.
     * Score for document d = sum over lists: 1 / (k + rank(d)).
     * Ties are broken by the higher original score.
     */
    List<SearchResult> rrfMerge(List<SearchResult> vectorResults,
                                List<SearchResult> keywordResults,
                                int k) {
        Map<UUID, Double> rrfScores = new LinkedHashMap<>();
        Map<UUID, SearchResult> bestResult = new LinkedHashMap<>();

        accumulateRrf(vectorResults, k, rrfScores, bestResult);
        accumulateRrf(keywordResults, k, rrfScores, bestResult);

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed()
                        .thenComparing(e -> -bestResult.get(e.getKey()).score()))
                .map(e -> {
                    SearchResult original = bestResult.get(e.getKey());
                    return new SearchResult(
                            original.observationId(),
                            original.tableName(),
                            e.getValue(),
                            original.riskScore(),
                            original.factorsText(),
                            original.observationTime(),
                            original.routeId(),
                            original.metadata());
                })
                .toList();
    }

    /** Returns true if the search backend is reachable and indexes exist. */
    public boolean isAvailable() {
        try {
            // Lightweight check: attempt a trivial keyword search
            vectorRepo.searchByKeyword("health", DEFAULT_TABLE, 1);
            return true;
        } catch (Exception ex) {
            log.debug("Search backend unavailable: {}", ex.toString());
            return false;
        }
    }

    /** Returns the number of indexed documents, or -1 if unknown. */
    public long indexCount() {
        return -1; // Not implemented; would require a COUNT query
    }

    // ---- Internals ----

    private void accumulateRrf(List<SearchResult> results, int k,
                               Map<UUID, Double> scores,
                               Map<UUID, SearchResult> best) {
        for (int rank = 0; rank < results.size(); rank++) {
            SearchResult r = results.get(rank);
            double rrfScore = 1.0 / (k + rank + 1);
            scores.merge(r.observationId(), rrfScore, Double::sum);
            // Keep the result with the higher original score
            best.merge(r.observationId(), r,
                    (existing, candidate) -> candidate.score() > existing.score() ? candidate : existing);
        }
    }
}
