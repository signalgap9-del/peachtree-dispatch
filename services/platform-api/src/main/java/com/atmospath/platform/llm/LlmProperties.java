package com.atmospath.platform.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the OpenAI-compatible LLM backend (LiteLLM Proxy).
 * All beans in the {@code llm} package are conditional on {@code enabled=true}
 * so the rest of the application is unaffected when LLM is off.
 */
@ConfigurationProperties(prefix = "atmospath.llm")
public record LlmProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String model,
        int maxTokens,
        double temperature,
        long timeoutMs,
        long dailyTokenBudget,
        int requestTokenLimit) {

    public LlmProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:4000";
        }
        if (apiKey == null) {
            apiKey = "";
        }
        if (model == null || model.isBlank()) {
            model = "gpt-4o-mini";
        }
        if (maxTokens <= 0) {
            maxTokens = 1024;
        }
        if (temperature < 0) {
            temperature = 0.7;
        }
        if (timeoutMs <= 0) {
            timeoutMs = 60_000L;
        }
        if (dailyTokenBudget <= 0) {
            dailyTokenBudget = 100_000L;
        }
        if (requestTokenLimit <= 0) {
            requestTokenLimit = 4096;
        }
    }
}
