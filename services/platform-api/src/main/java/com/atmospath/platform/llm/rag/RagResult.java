package com.atmospath.platform.llm.rag;

import java.util.List;

/**
 * The output of a RAG augmentation: assembled context string for prompt
 * injection, the source references for citation tracking, and search
 * metadata.
 */
public record RagResult(
        String context,
        List<SourceReference> sources,
        long searchLatencyMs,
        int resultCount) {

    public RagResult {
        if (sources == null) {
            sources = List.of();
        }
    }

    public static RagResult empty(long latencyMs) {
        return new RagResult("No relevant historical data found.", List.of(), latencyMs, 0);
    }
}
