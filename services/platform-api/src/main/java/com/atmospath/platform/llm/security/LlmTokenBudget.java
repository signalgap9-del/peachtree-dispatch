package com.atmospath.platform.llm.security;

/** Token budget gate checked by {@link LlmSecurityFilter} before LLM requests proceed. */
public interface LlmTokenBudget {
    boolean tryConsume(String tenantId);

    long retryAfterSeconds();
}
