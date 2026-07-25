package com.atmospath.platform.llm.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.atmospath.platform.llm.LlmClient;
import com.atmospath.platform.llm.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Reranks candidate search results using an LLM as a cross-encoder proxy.
 * Asks the LLM to order observation IDs by relevance to the query, then
 * reorders the candidate list accordingly. Falls back to the original
 * RRF ordering if the LLM call fails.
 */
@Service
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
public class CrossEncoderReranker {

    private static final Logger log = LoggerFactory.getLogger(CrossEncoderReranker.class);
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final LlmClient llmClient;

    public CrossEncoderReranker(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * Reranks candidates against the query using the LLM and returns the
     * top {@code limit} results in order of relevance.
     *
     * @param query      the original user query
     * @param candidates pre-filtered search results from hybrid search
     * @param limit      maximum number of results to keep
     * @return reranked results ordered by relevance descending
     */
    public List<SearchResult> rerank(String query, List<SearchResult> candidates, int limit) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        try {
            String prompt = buildRerankPrompt(query, candidates);
            String response = llmClient.complete(List.of(
                    Message.system("You are a relevance ranker. Order the observation IDs from most to least relevant. Output ONLY comma-separated IDs."),
                    Message.user(prompt)));

            List<UUID> orderedIds = parseIdList(response);
            List<SearchResult> reranked = reorderByIds(candidates, orderedIds);
            return reranked.stream().limit(limit).toList();
        } catch (Exception ex) {
            log.warn("Reranker LLM call failed; falling back to original order: {}", ex.toString());
            return candidates.stream().limit(limit).toList();
        }
    }

    /**
     * Parses UUIDs from an LLM response, tolerating numbering artifacts
     * like "1. uuid" or "2. uuid".
     */
    List<UUID> parseIdList(String response) {
        List<UUID> ids = new ArrayList<>();
        Matcher matcher = UUID_PATTERN.matcher(response);
        while (matcher.find()) {
            try {
                ids.add(UUID.fromString(matcher.group()));
            } catch (IllegalArgumentException ignored) {
                // Skip malformed UUIDs
            }
        }
        return ids;
    }

    // ---- Internals ----

    private String buildRerankPrompt(String query, List<SearchResult> candidates) {
        var sb = new StringBuilder();
        sb.append("Query: ").append(query).append("\n\n");
        sb.append("Observations (ID: summary):\n");
        for (SearchResult c : candidates) {
            sb.append(c.observationId()).append(": ")
              .append("risk ").append(c.riskScore()).append("/100");
            if (c.factorsText() != null && !c.factorsText().isBlank()) {
                sb.append(", ").append(c.factorsText());
            }
            sb.append("\n");
        }
        sb.append("\nOrder these observation IDs from most to least relevant to the query.");
        return sb.toString();
    }

    private List<SearchResult> reorderByIds(List<SearchResult> candidates, List<UUID> orderedIds) {
        Map<UUID, SearchResult> byId = new LinkedHashMap<>();
        for (SearchResult c : candidates) {
            byId.put(c.observationId(), c);
        }

        List<SearchResult> result = new ArrayList<>();
        // Add in LLM-specified order
        for (UUID id : orderedIds) {
            SearchResult match = byId.remove(id);
            if (match != null) {
                result.add(match);
            }
        }
        // Append any remaining candidates not mentioned by the LLM
        result.addAll(byId.values());
        return result;
    }
}
