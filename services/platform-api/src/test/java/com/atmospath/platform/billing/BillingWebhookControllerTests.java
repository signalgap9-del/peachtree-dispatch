package com.atmospath.platform.billing;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BillingWebhookControllerTests {

    private static final String SECRET = "test-webhook-secret";

    private SubscriptionSyncService syncService;
    private MockMvc mockMvc;
    private WebhookSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new WebhookSignatureVerifier(SECRET);
        syncService = mock(SubscriptionSyncService.class);
        BillingWebhookController controller =
                new BillingWebhookController(verifier, syncService, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void invalidSignatureReturns401() throws Exception {
        String body = subscriptionCreatedPayload();

        mockMvc.perform(post("/api/v1/billing/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", "invalid-signature")
                        .content(body))
                .andExpect(status().isUnauthorized());

        verify(syncService, never()).handleEvent(anyString(), anyMap());
    }

    @Test
    void missingSignatureReturns401() throws Exception {
        String body = subscriptionCreatedPayload();

        mockMvc.perform(post("/api/v1/billing/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        verify(syncService, never()).handleEvent(anyString(), anyMap());
    }

    @Test
    void validSignatureReturns200AndDispatchesEvent() throws Exception {
        String body = subscriptionCreatedPayload();
        String signature = verifier.sign(body);

        mockMvc.perform(post("/api/v1/billing/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", signature)
                        .content(body))
                .andExpect(status().isOk());

        verify(syncService).handleEvent(eq("subscription_created"), anyMap());
    }

    @Test
    void tamperedBodyReturns401() throws Exception {
        String body = subscriptionCreatedPayload();
        String signature = verifier.sign(body);
        String tampered = body.replace("subscription_created", "subscription_cancelled");

        mockMvc.perform(post("/api/v1/billing/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", signature)
                        .content(tampered))
                .andExpect(status().isUnauthorized());

        verify(syncService, never()).handleEvent(anyString(), anyMap());
    }

    private static String subscriptionCreatedPayload() {
        return """
                {
                  "meta": {
                    "event_name": "subscription_created",
                    "custom_data": {
                      "subject": "google-oauth2|user123",
                      "email": "user@example.com"
                    }
                  },
                  "data": {
                    "type": "subscriptions",
                    "id": "ls-sub-1",
                    "attributes": {
                      "status": "active",
                      "customer_id": "cust-1",
                      "variant_id": "variant-pro-1",
                      "renews_at": "2026-08-01T00:00:00Z",
                      "cancelled": false
                    }
                  }
                }
                """;
    }
}
