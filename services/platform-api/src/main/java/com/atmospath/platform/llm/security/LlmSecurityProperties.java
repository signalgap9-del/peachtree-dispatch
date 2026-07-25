package com.atmospath.platform.llm.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atmospath.llm.security")
public record LlmSecurityProperties(
        List<String> blockedInputPatterns,
        List<String> blockedOutputPatterns,
        int maxInputLength,
        int maxOutputLength) {

    public static final List<String> DEFAULT_BLOCKED_INPUT_PATTERNS = List.of(
            "ignore previous",
            "system prompt",
            "you are now",
            "disregard instructions",
            "jailbreak",
            "DAN mode");

    public static final List<String> DEFAULT_BLOCKED_OUTPUT_PATTERNS = List.of(
            "you must immediately",
            "ignore traffic laws",
            "it is safe to drive through flood water",
            "you can exceed speed limit");

    public LlmSecurityProperties {
        blockedInputPatterns = (blockedInputPatterns == null || blockedInputPatterns.isEmpty())
                ? DEFAULT_BLOCKED_INPUT_PATTERNS
                : List.copyOf(blockedInputPatterns);
        blockedOutputPatterns = (blockedOutputPatterns == null || blockedOutputPatterns.isEmpty())
                ? DEFAULT_BLOCKED_OUTPUT_PATTERNS
                : List.copyOf(blockedOutputPatterns);
        maxInputLength = maxInputLength <= 0 ? 2000 : maxInputLength;
        maxOutputLength = maxOutputLength <= 0 ? 5000 : maxOutputLength;
    }
}
