package com.atmospath.platform.llm.rag;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.atmospath.platform.llm.LiteLlmClient;
import com.atmospath.platform.llm.Message;
import com.atmospath.platform.llm.prompt.PromptTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Retrieval-Augmented Generation pipeline for route risk explanations.
 * Retrieves relevant historical observations via hybrid search, reranks
 * them, assembles a context string, and augments LLM prompts with
 * grounded historical data.
 *
 * <p>Pipeline stages:
 * <ol>
 *   <li>Query expansion (LLM or static synonym map)</li>
 *   <li>Hybrid search (vector + keyword + RRF)</li>
 *   <li>Cross-encoder reranking (top 10 → top 3)</li>
 *   <li>Context assembly from top results</li>
 * </ol>
 */
@Service
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int SEARCH_LIMIT = 10;
    private static final int RERANK_LIMIT = 3;
    private static final String RAG_CONTEXT_TEMPLATE = "rag_context";

    /** Static synonym map used when LLM-based expansion is unavailable. */
    private static final Map<String, List<String>> SYNONYM_MAP = Map.ofEntries(
            Map.entry("flood", List.of("flooding", "water on road", "flash flood", "inundation")),
            Map.entry("snow", List.of("snowfall", "blizzard", "winter storm", "ice")),
            Map.entry("wind", List.of("gust", "high wind", "windstorm", "crosswind")),
            Map.entry("rain", List.of("precipitation", "downpour", "heavy rain", "storm")),
            Map.entry("fog", List.of("low visibility", "dense fog", "mist")),
            Map.entry("ice", List.of("black ice", "freezing rain", "icy road", "glaze")),
            Map.entry("heat", List.of("extreme heat", "high temperature", "heatwave")),
            Map.entry("traffic", List.of("congestion", "accident", "collision", "road closure")));

    private final ObjectProvider<HybridSearchService> searchProvider;
    private final ObjectProvider<CrossEncoderReranker> rerankerProvider;
    private final ObjectProvider<LiteLlmClient> llmProvider;
    private final PromptTemplateService templates;

    public RagService(ObjectProvider<HybridSearchService> searchProvider,
                      ObjectProvider<CrossEncoderReranker> rerankerProvider,
                      ObjectProvider<LiteLlmClient> llmProvider,
                      PromptTemplateService templates) {
        this.searchProvider = searchProvider;
        this.rerankerProvider = rerankerProvider;
        this.llmProvider = llmProvider;
        this.templates = templates;
    }

    // ---- Main entry point ----

    /**
     * Retrieves relevant historical context for a query and assembles
     * a {@link RagResult} with the context string and source references.
     */
    public RagResult augment(String query, SearchFilters filters) {
        long startNanos = System.nanoTime();

        HybridSearchService search = searchProvider.getIfAvailable();
        if (search == null || !search.isAvailable()) {
            log.debug("Hybrid search unavailable; returning empty RAG result");
            return RagResult.empty(elapsedMs(startNanos));
        }

        // Stage 1: Query expansion
        String expandedQuery = expandQuery(query);

        // Stage 2: Hybrid search
        List<SearchResult> candidates = search.search(expandedQuery, filters, SEARCH_LIMIT);
        if (candidates.isEmpty()) {
            log.debug("No search results for query: {}", query);
            return RagResult.empty(elapsedMs(startNanos));
        }

        // Stage 3: Rerank
        List<SearchResult> topResults = rerank(query, candidates);

        // Stage 4: Assemble context and sources
        String context = assembleContext(topResults);
        List<SourceReference> sources = topResults.stream()
                .map(RagService::toSourceReference)
                .toList();

        long latencyMs = elapsedMs(startNanos);
        log.info("RAG augment: query='{}', {} candidates → {} results, {}ms",
                query, candidates.size(), topResults.size(), latencyMs);

        return new RagResult(context, sources, latencyMs, topResults.size());
    }

    // ---- Prompt building ----

    /**
     * Builds chat messages for a prompt template augmented with RAG context.
     * Injects the historical observations into the prompt and adds citation
     * instructions.
     */
    public List<Message> buildRagPrompt(String templateName, Map<String, String> variables,
                                        RagResult ragResult) {
        List<Message> baseMessages = templates.buildMessages(templateName, variables);

        if (ragResult.resultCount() == 0) {
            return baseMessages;
        }

        // Build the RAG context block
        String contextBlock = buildContextBlock(ragResult.context());

        // Augment the system message with RAG instructions and context
        Message system = baseMessages.get(0);
        String augmentedSystem = system.content() + "\n\n" + contextBlock;

        var result = new ArrayList<>(baseMessages);
        result.set(0, Message.system(augmentedSystem));
        return List.copyOf(result);
    }

    // ---- Query expansion ----

    /**
     * Expands the query with synonyms. Tries LLM-based expansion first,
     * falls back to the static synonym map.
     */
    String expandQuery(String query) {
        // Try LLM expansion
        LiteLlmClient llm = llmProvider.getIfAvailable();
        if (llm != null) {
            try {
                String expanded = llmExpand(llm, query);
                if (expanded != null && !expanded.isBlank()) {
                    log.debug("LLM query expansion: '{}' → '{}'", query, expanded);
                    return expanded;
                }
            } catch (Exception ex) {
                log.debug("LLM query expansion failed, using synonym map: {}", ex.toString());
            }
        }

        // Fallback: static synonym map
        return synonymExpand(query);
    }

    private String llmExpand(LiteLlmClient llm, String query) {
        List<Message> messages = List.of(
                Message.system("You are a search query expander. Given a route risk query, "
                        + "add 2-3 relevant synonym phrases separated by spaces. "
                        + "Output ONLY the expanded query, nothing else. Keep it under 30 words."),
                Message.user(query));
        return llm.complete(messages);
    }

    /**
     * Expands the query using the static synonym map. Matches keywords
     * case-insensitively and appends their synonyms.
     */
    String synonymExpand(String query) {
        String lower = query.toLowerCase();
        List<String> expansions = new ArrayList<>();
        expansions.add(query);

        for (Map.Entry<String, List<String>> entry : SYNONYM_MAP.entrySet()) {
            if (lower.contains(entry.getKey())) {
                // Add up to 2 synonyms to avoid over-expansion
                entry.getValue().stream().limit(2).forEach(expansions::add);
            }
        }

        return expansions.stream().distinct().collect(Collectors.joining(" "));
    }

    // ---- Reranking ----

    private List<SearchResult> rerank(String query, List<SearchResult> candidates) {
        CrossEncoderReranker reranker = rerankerProvider.getIfAvailable();
        if (reranker == null) {
            log.debug("No reranker available; taking top {} by RRF score", RERANK_LIMIT);
            return candidates.stream().limit(RERANK_LIMIT).toList();
        }
        try {
            return reranker.rerank(query, candidates, RERANK_LIMIT);
        } catch (Exception ex) {
            log.warn("Reranker failed; falling back to RRF order: {}", ex.toString());
            return candidates.stream().limit(RERANK_LIMIT).toList();
        }
    }

    // ---- Context assembly ----

    /**
     * Assembles a human-readable context string from the top search results.
     * Format: "[Past observation N] date, route: risk X/100, factors: ..."
     */
    String assembleContext(List<SearchResult> results) {
        if (results.isEmpty()) {
            return "No relevant historical data found.";
        }
        return IntStream.range(0, results.size())
                .mapToObj(i -> formatObservation(i + 1, results.get(i)))
                .collect(Collectors.joining("\n\n"));
    }

    private String formatObservation(int index, SearchResult result) {
        var sb = new StringBuilder();
        sb.append(String.format("[Past observation %d] %s, %s: risk %d/100",
                index,
                DATE_FMT.format(result.observationTime().atZone(java.time.ZoneOffset.UTC)),
                result.routeId(),
                result.riskScore()));
        if (result.factorsText() != null && !result.factorsText().isBlank()) {
            sb.append(", factors: ").append(result.factorsText());
        }
        if (result.summary() != null && !result.summary().isBlank()) {
            sb.append("\n  ").append(result.summary());
        }
        return sb.toString();
    }

    private String buildContextBlock(String ragContext) {
        // Try to use the rag_context template if available
        try {
            var template = templates.template(RAG_CONTEXT_TEMPLATE);
            String system = template.system();
            String contextBlock = template.user()
                    .replace("{rag_context}", ragContext);
            return system + "\n\n" + contextBlock;
        } catch (IllegalArgumentException ex) {
            // Template not found; use inline instructions
            return "You have access to historical route risk observations.\n"
                    + "Use them to ground your response. Cite observation dates and locations.\n"
                    + "Do not fabricate historical events not in the provided data.\n\n"
                    + "Historical observations (most relevant first):\n" + ragContext;
        }
    }

    // ---- Helpers ----

    private static SourceReference toSourceReference(SearchResult result) {
        return new SourceReference(
                result.observationId(),
                result.tableName(),
                result.observationTime(),
                result.routeId(),
                result.riskScore(),
                result.score(),
                result.summary());
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
