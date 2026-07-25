package com.atmospath.platform.llm.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmOutputValidatorTests {
    private final LlmOutputValidator validator = new LlmOutputValidator(
            new LlmSecurityProperties(null, null, 0, 0));

    @Test
    void normalExplanationPasses() {
        var result = validator.validate("The route has moderate risk due to rain. Drive carefully.");
        assertThat(result.isClean()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void dangerousFloodAdviceFlagged() {
        var result = validator.validate("it is safe to drive through flood water on this route");
        assertThat(result.isClean()).isFalse();
        assertThat(result.issues()).anyMatch(i -> i.contains("dangerous advice"));
    }

    @Test
    void phoneNumberMasked() {
        var result = validator.validate("Call 010-1234-5678 for details");
        assertThat(result.sanitizedOutput()).contains("[REDACTED]");
        assertThat(result.sanitizedOutput()).doesNotContain("010-1234-5678");
        assertThat(result.issues()).anyMatch(i -> i.contains("phone"));
    }

    @Test
    void urlStripped() {
        var result = validator.validate("Visit https://example.com/evil for more info");
        assertThat(result.sanitizedOutput()).doesNotContain("https://");
        assertThat(result.issues()).anyMatch(i -> i.contains("URL"));
    }

    @Test
    void overLengthTruncated() {
        var result = validator.validate("x".repeat(5001));
        assertThat(result.sanitizedOutput()).hasSize(5000);
        assertThat(result.issues()).anyMatch(i -> i.contains("truncated"));
    }

    @Test
    void koreanOutputPassesCleanly() {
        var input = "현재 경로에 비가 내리고 있습니다. 안전 운전에 유의하세요.";
        var result = validator.validate(input);
        assertThat(result.isClean()).isTrue();
        assertThat(result.sanitizedOutput()).isEqualTo(input);
    }
}
