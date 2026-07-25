package com.atmospath.platform.llm.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the RAG (Retrieval-Augmented Generation) subsystem.
 * All RAG beans are conditional on {@code enabled=true}.
 */
@ConfigurationProperties(prefix = "atmospath.llm.rag")
public record RagProperties(
        boolean enabled,
        String embeddingModel,
        int embeddingDimensions,
        int searchTopK,
        int rerankTopK,
        int rrfK,
        double similarityThreshold) {

    public RagProperties {
        if (embeddingModel == null || embeddingModel.isBlank()) {
            embeddingModel = "text-embedding-v3";
        }
        if (embeddingDimensions <= 0) {
            embeddingDimensions = 384;
        }
        if (searchTopK <= 0) {
            searchTopK = 10;
        }
        if (rerankTopK <= 0) {
            rerankTopK = 3;
        }
        if (rrfK <= 0) {
            rrfK = 60;
        }
        if (similarityThreshold < 0) {
            similarityThreshold = 0.3;
        }
    }
}
