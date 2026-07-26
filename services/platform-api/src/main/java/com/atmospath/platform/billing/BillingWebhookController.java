package com.atmospath.platform.billing;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives Lemon Squeezy webhook events. The raw body is verified against
 * the {@code X-Signature} HMAC-SHA256 header before any processing.
 */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingWebhookController {

    private static final Logger log = LoggerFactory.getLogger(BillingWebhookController.class);

    private final WebhookSignatureVerifier signatureVerifier;
    private final SubscriptionSyncService syncService;
    private final ObjectMapper objectMapper;

    public BillingWebhookController(WebhookSignatureVerifier signatureVerifier,
                                    SubscriptionSyncService syncService,
                                    ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.syncService = syncService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/webhook")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Void> handleWebhook(
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestBody String rawBody) {
        if (!signatureVerifier.verify(rawBody, signature)) {
            log.warn("Rejected webhook with invalid or missing signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            Map<String, Object> payload = objectMapper.readValue(rawBody, Map.class);
            Map<String, Object> meta = (Map<String, Object>) payload.getOrDefault("meta", Map.of());
            String eventName = String.valueOf(meta.getOrDefault("event_name", "unknown"));

            log.info("Processing billing webhook event: {}", eventName);
            syncService.handleEvent(eventName, payload);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            log.error("Webhook payload missing required fields: {}", e.getMessage());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to process billing webhook", e);
            return ResponseEntity.ok().build();
        }
    }
}
