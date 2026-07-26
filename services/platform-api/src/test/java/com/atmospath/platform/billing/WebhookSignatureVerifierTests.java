package com.atmospath.platform.billing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WebhookSignatureVerifierTests {

    private static final String SECRET = "test-webhook-secret";
    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(SECRET);

    @Test
    void validSignaturePasses() {
        String body = "{\"meta\":{\"event_name\":\"subscription_created\"}}";
        String signature = verifier.sign(body);

        assertThat(verifier.verify(body, signature)).isTrue();
    }

    @Test
    void tamperedBodyFails() {
        String body = "{\"meta\":{\"event_name\":\"subscription_created\"}}";
        String signature = verifier.sign(body);
        String tampered = "{\"meta\":{\"event_name\":\"subscription_cancelled\"}}";

        assertThat(verifier.verify(tampered, signature)).isFalse();
    }

    @Test
    void wrongSecretFails() {
        String body = "{\"meta\":{\"event_name\":\"subscription_created\"}}";
        WebhookSignatureVerifier otherVerifier = new WebhookSignatureVerifier("wrong-secret");
        String signature = otherVerifier.sign(body);

        assertThat(verifier.verify(body, signature)).isFalse();
    }

    @Test
    void nullSignatureFails() {
        assertThat(verifier.verify("body", null)).isFalse();
    }

    @Test
    void blankSignatureFails() {
        assertThat(verifier.verify("body", "  ")).isFalse();
    }

    @Test
    void nullBodyFails() {
        assertThat(verifier.verify(null, "abc")).isFalse();
    }

    @Test
    void malformedHexSignatureFails() {
        assertThat(verifier.verify("body", "not-hex!")).isFalse();
    }

    @Test
    void oddLengthHexSignatureFails() {
        assertThat(verifier.verify("body", "abc")).isFalse();
    }

    @Test
    void signatureIsCaseInsensitiveHex() {
        String body = "test-body";
        String signature = verifier.sign(body).toUpperCase();

        assertThat(verifier.verify(body, signature)).isTrue();
    }
}
