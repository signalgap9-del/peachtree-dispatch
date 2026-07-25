package com.atmospath.platform.llm.security;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Defense against prompt injection. Rejects over-length input, detects known
 * injection phrases, and strips control characters while preserving normal
 * punctuation and Unicode (Korean text passes cleanly).
 */
@Component
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
public class LlmInputSanitizer {
    private static final Logger log = LoggerFactory.getLogger(LlmInputSanitizer.class);

    private final List<String> patternLabels;
    private final List<Pattern> compiledPatterns;
    private final int maxInputLength;

    public LlmInputSanitizer(LlmSecurityProperties properties) {
        this.maxInputLength = properties.maxInputLength();
        this.patternLabels = properties.blockedInputPatterns();
        this.compiledPatterns = patternLabels.stream()
                .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
                .toList();
    }

    public SanitizedInput sanitize(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return new SanitizedInput(userMessage, "", false, List.of("empty input"));
        }

        // 1. Length check
        if (userMessage.length() > maxInputLength) {
            log.warn("LLM input rejected: length {} exceeds maximum {}", userMessage.length(), maxInputLength);
            return new SanitizedInput(userMessage, userMessage, false,
                    List.of("input exceeds maximum length of " + maxInputLength));
        }

        // 2. Known injection pattern detection
        var detected = new ArrayList<String>();
        for (int i = 0; i < compiledPatterns.size(); i++) {
            if (compiledPatterns.get(i).matcher(userMessage).find()) {
                detected.add(patternLabels.get(i));
            }
        }
        if (!detected.isEmpty()) {
            log.warn("LLM input rejected: prompt injection patterns detected: {}", detected);
            return new SanitizedInput(userMessage, userMessage, false, List.copyOf(detected));
        }

        // 3. Strip control characters and null bytes (preserve newlines, tabs, Korean, punctuation)
        var sanitized = stripControlCharacters(userMessage);
        return new SanitizedInput(userMessage, sanitized, true, List.of());
    }

    private static String stripControlCharacters(String input) {
        var sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || !Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public record SanitizedInput(
            String originalText,
            String sanitizedText,
            boolean isSafe,
            List<String> detectedPatterns) {
    }
}
