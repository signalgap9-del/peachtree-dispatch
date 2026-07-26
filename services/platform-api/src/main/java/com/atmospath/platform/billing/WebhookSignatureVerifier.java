package com.atmospath.platform.billing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies Lemon Squeezy webhook HMAC-SHA256 signatures using constant-time
 * comparison to prevent timing attacks.
 */
public class WebhookSignatureVerifier {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secretBytes;

    public WebhookSignatureVerifier(String webhookSecret) {
        this.secretBytes = webhookSecret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns true when {@code signatureHeader} matches the hex-encoded
     * HMAC-SHA256 of {@code rawBody} under the configured secret.
     */
    public boolean verify(String rawBody, String signatureHeader) {
        if (rawBody == null || signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        byte[] expected = computeHmac(rawBody.getBytes(StandardCharsets.UTF_8));
        byte[] provided = hexToBytes(signatureHeader.trim());
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(expected, provided);
    }

    /** Computes the hex-encoded HMAC-SHA256 for external use (e.g. tests). */
    public String sign(String body) {
        return bytesToHex(computeHmac(body.getBytes(StandardCharsets.UTF_8)));
    }

    private byte[] computeHmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretBytes, HMAC_ALGORITHM));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 computation failed", e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        try {
            int length = hex.length();
            if (length % 2 != 0) {
                return null;
            }
            byte[] result = new byte[length / 2];
            for (int i = 0; i < length; i += 2) {
                result[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                        + Character.digit(hex.charAt(i + 1), 16));
            }
            return result;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
