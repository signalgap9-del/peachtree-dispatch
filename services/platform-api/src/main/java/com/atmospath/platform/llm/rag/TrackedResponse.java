package com.atmospath.platform.llm.rag;

import java.util.List;

/**
 * An LLM response annotated with citation tracking: which historical
 * sources were actually referenced in the generated text.
 */
public record TrackedResponse(
        String response,
        List<SourceReference> citedSources,
        String citationFooter) {

    public TrackedResponse {
        if (citedSources == null) {
            citedSources = List.of();
        }
    }
}
