package com.atmospath.platform.llm.security;

/**
 * Renders raw structured data without LLM explanation. Each feature implements
 * this to provide a degraded-but-useful response when the LLM is unavailable.
 */
@FunctionalInterface
public interface StructuredFallback {
    String render();
}
