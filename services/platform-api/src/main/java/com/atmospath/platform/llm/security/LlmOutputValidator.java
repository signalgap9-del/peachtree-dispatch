package com.atmospath.platform.llm.security;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Validates LLM responses before they reach the user: blocks dangerous driving
 * advice, masks PII, strips injected URLs, truncates over-length output, and
 * rejects control characters.
 */
@Component
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
public class LlmOutputValidator {
    private static final Logger log = LoggerFactory.getLogger(LlmOutputValidator.class);
    private static final String REDACTED = "[REDACTED]";

    // Korean (010-1234-5678) and US (123-456-7890) phone formats
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("\\b\\d{3}[-.]\\d{3,4}[-.]\\d{4}\\b");
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern SSN_PATTERN =
            Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://\\S+");

    private final List<String> dangerousLabels;
    private final List<Pattern> dangerousPatterns;
    private final int maxOutputLength;

    public LlmOutputValidator(LlmSecurityProperties properties) {
        this.maxOutputLength = properties.maxOutputLength();
        this.dangerousLabels = properties.blockedOutputPatterns();
        this.dangerousPatterns = dangerousLabels.stream()
                .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
                .toList();
    }

    public ValidationResult validate(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return new ValidationResult("", true, List.of());
        }

        var issues = new ArrayList<String>();
        var output = llmOutput;

        // 1. Dangerous driving advice filter
        for (int i = 0; i < dangerousPatterns.size(); i++) {
            if (dangerousPatterns.get(i).matcher(output).find()) {
                issues.add("dangerous advice: " + dangerousLabels.get(i));
            }
        }

        // 2. PII detection and masking
        if (PHONE_PATTERN.matcher(output).find()) {
            issues.add("PII: phone number masked");
            output = PHONE_PATTERN.matcher(output).replaceAll(REDACTED);
        }
        if (EMAIL_PATTERN.matcher(output).find()) {
            issues.add("PII: email address masked");
            output = EMAIL_PATTERN.matcher(output).replaceAll(REDACTED);
        }
        if (SSN_PATTERN.matcher(output).find()) {
            issues.add("PII: SSN pattern masked");
            output = SSN_PATTERN.matcher(output).replaceAll(REDACTED);
        }

        // 3. URL injection: strip any URLs (LLM should not generate links)
        if (URL_PATTERN.matcher(output).find()) {
            issues.add("URL injection: links stripped");
            output = URL_PATTERN.matcher(output).replaceAll("").stripTrailing();
        }

        // 4. Length check: truncate
        if (output.length() > maxOutputLength) {
            issues.add("output truncated to " + maxOutputLength + " characters");
            output = output.substring(0, maxOutputLength);
        }

        // 5. Encoding check: reject if contains control characters
        if (containsControlCharacters(output)) {
            issues.add("control characters detected in output");
        }

        if (!issues.isEmpty()) {
            log.warn("LLM output validation issues: {}", issues);
        }

        return new ValidationResult(output, issues.isEmpty(), List.copyOf(issues));
    }

    private static boolean containsControlCharacters(String input) {
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c != '\n' && c != '\r' && c != '\t' && Character.isISOControl(c)) {
                return true;
            }
        }
        return false;
    }

    public record ValidationResult(
            String sanitizedOutput,
            boolean isClean,
            List<String> issues) {
    }
}
