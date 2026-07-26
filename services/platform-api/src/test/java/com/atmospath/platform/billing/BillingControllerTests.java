package com.atmospath.platform.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.atmospath.platform.billing.dto.CheckoutRequest;
import com.atmospath.platform.billing.dto.CheckoutResponse;
import com.atmospath.platform.billing.dto.SubscriptionResponse;
import com.atmospath.platform.saas.entity.Subscription;
import com.atmospath.platform.saas.entity.SubscriptionPlan;
import com.atmospath.platform.saas.entity.SubscriptionState;
import com.atmospath.platform.saas.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class BillingControllerTests {

    private LemonSqueezyClient client;
    private SubscriptionRepository repository;
    private BillingController controller;

    private static final LemonSqueezyProperties PROPERTIES = new LemonSqueezyProperties(
            true, "lemonsqueezy", "https://api.lemonsqueezy.com/v1",
            "test-key", "store-1", "secret", "variant-pro-1");

    @BeforeEach
    void setUp() {
        client = mock(LemonSqueezyClient.class);
        repository = mock(SubscriptionRepository.class);
        controller = new BillingController(client, PROPERTIES, repository);
    }

    @Test
    void checkoutReturnsUrl() {
        when(client.createCheckout(eq("store-1"), eq("variant-pro-1"), anyMap()))
                .thenReturn(Map.of(
                        "data", Map.of(
                                "type", "checkouts",
                                "id", "checkout-1",
                                "attributes", Map.of(
                                        "url", "https://checkout.lemonsqueezy.com/checkout/abc"))));

        CheckoutResponse response = controller.createCheckout(jwt(), new CheckoutRequest(null));

        assertThat(response.checkoutUrl())
                .isEqualTo("https://checkout.lemonsqueezy.com/checkout/abc");
    }

    @Test
    void checkoutUsesExplicitVariantId() {
        when(client.createCheckout(eq("store-1"), eq("custom-variant"), anyMap()))
                .thenReturn(Map.of(
                        "data", Map.of(
                                "attributes", Map.of(
                                        "url", "https://checkout.lemonsqueezy.com/checkout/xyz"))));

        CheckoutResponse response = controller.createCheckout(
                jwt(), new CheckoutRequest("custom-variant"));

        assertThat(response.checkoutUrl())
                .isEqualTo("https://checkout.lemonsqueezy.com/checkout/xyz");
    }

    @Test
    void subscriptionReturnsCurrentPlan() {
        UUID tenantId = tenantId();
        Subscription subscription = new Subscription(
                tenantId, SubscriptionPlan.PRO, SubscriptionState.ACTIVE, null, null);
        subscription.setBillingStatus("active");
        subscription.setBillingPeriodEnd(Instant.parse("2026-08-01T00:00:00Z"));
        subscription.setCancelAtPeriodEnd(false);

        when(repository.findActiveByTenantId(tenantId))
                .thenReturn(Optional.of(subscription));

        SubscriptionResponse response = controller.getSubscription(jwt());

        assertThat(response.plan()).isEqualTo("pro");
        assertThat(response.status()).isEqualTo("active");
        assertThat(response.currentPeriodEnd())
                .isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(response.cancelAtPeriodEnd()).isFalse();
    }

    @Test
    void subscriptionReturnsFreeWhenNoSubscription() {
        when(repository.findActiveByTenantId(tenantId()))
                .thenReturn(Optional.empty());

        SubscriptionResponse response = controller.getSubscription(jwt());

        assertThat(response.plan()).isEqualTo("free");
        assertThat(response.status()).isEqualTo("none");
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("google-oauth2|user123")
                .claim("email", "user@example.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private static UUID tenantId() {
        return UUID.nameUUIDFromBytes(
                "tenant:google-oauth2|user123".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
