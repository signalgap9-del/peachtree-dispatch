package com.atmospath.platform.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.atmospath.platform.saas.entity.Subscription;
import com.atmospath.platform.saas.entity.SubscriptionPlan;
import com.atmospath.platform.saas.entity.SubscriptionState;
import com.atmospath.platform.saas.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SubscriptionSyncServiceTests {

    private SubscriptionRepository repository;
    private SubscriptionSyncService service;

    @BeforeEach
    void setUp() {
        repository = mock(SubscriptionRepository.class);
        LemonSqueezyProperties properties = new LemonSqueezyProperties(
                true, "lemonsqueezy", "https://api.lemonsqueezy.com/v1",
                "test-key", "store-1", "secret", "variant-pro-1");
        service = new SubscriptionSyncService(repository, properties);
    }

    @Test
    void subscriptionCreatedCreatesProRow() {
        when(repository.findByLemonsqueezySubscriptionId("ls-sub-1"))
                .thenReturn(Optional.empty());
        when(repository.save(any(Subscription.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.handleEvent("subscription_created", webhookPayload(
                "ls-sub-1", "active", "cust-1", "variant-pro-1",
                "2026-08-01T00:00:00Z", null, false));

        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(repository).save(captor.capture());
        Subscription saved = captor.getValue();

        assertThat(saved.getLemonsqueezySubscriptionId()).isEqualTo("ls-sub-1");
        assertThat(saved.getPlan()).isEqualTo(SubscriptionPlan.PRO);
        assertThat(saved.getStatus()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(saved.getBillingStatus()).isEqualTo("active");
        assertThat(saved.getLemonsqueezyCustomerId()).isEqualTo("cust-1");
        assertThat(saved.getCancelAtPeriodEnd()).isFalse();
        assertThat(saved.isOpen()).isTrue();
    }

    @Test
    void subscriptionCancelledMarksCancelled() {
        Subscription existing = new Subscription(
                UUID.randomUUID(), SubscriptionPlan.PRO, SubscriptionState.ACTIVE, null, null);
        existing.setLemonsqueezySubscriptionId("ls-sub-1");
        when(repository.findByLemonsqueezySubscriptionId("ls-sub-1"))
                .thenReturn(Optional.of(existing));
        when(repository.save(any(Subscription.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.handleEvent("subscription_cancelled", webhookPayload(
                "ls-sub-1", "cancelled", "cust-1", "variant-pro-1",
                null, "2026-08-01T00:00:00Z", true));

        assertThat(existing.getStatus()).isEqualTo(SubscriptionState.CANCELLED);
        assertThat(existing.getBillingStatus()).isEqualTo("cancelled");
        assertThat(existing.getCancelAtPeriodEnd()).isTrue();
        assertThat(existing.isOpen()).isFalse();
    }

    @Test
    void idempotentSameEventTwiceProducesSameState() {
        when(repository.findByLemonsqueezySubscriptionId("ls-sub-1"))
                .thenReturn(Optional.empty());
        when(repository.save(any(Subscription.class)))
                .thenAnswer(inv -> {
                    Subscription sub = inv.getArgument(0);
                    // After first save, subsequent lookups return the saved entity
                    when(repository.findByLemonsqueezySubscriptionId("ls-sub-1"))
                            .thenReturn(Optional.of(sub));
                    return sub;
                });

        Map<String, Object> payload = webhookPayload(
                "ls-sub-1", "active", "cust-1", "variant-pro-1",
                "2026-08-01T00:00:00Z", null, false);

        service.handleEvent("subscription_created", payload);
        service.handleEvent("subscription_created", payload);

        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(repository, times(2)).save(captor.capture());

        Subscription first = captor.getAllValues().get(0);
        Subscription second = captor.getAllValues().get(1);

        // Same entity updated in place - same LS ID, same state
        assertThat(second.getLemonsqueezySubscriptionId())
                .isEqualTo(first.getLemonsqueezySubscriptionId());
        assertThat(second.getStatus()).isEqualTo(first.getStatus());
        assertThat(second.getPlan()).isEqualTo(first.getPlan());
        assertThat(second.getBillingStatus()).isEqualTo(first.getBillingStatus());
    }

    @Test
    void subscriptionExpiredMarksExpiredAndClosesRow() {
        Subscription existing = new Subscription(
                UUID.randomUUID(), SubscriptionPlan.PRO, SubscriptionState.ACTIVE, null, null);
        existing.setLemonsqueezySubscriptionId("ls-sub-1");
        when(repository.findByLemonsqueezySubscriptionId("ls-sub-1"))
                .thenReturn(Optional.of(existing));
        when(repository.save(any(Subscription.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.handleEvent("subscription_expired", webhookPayload(
                "ls-sub-1", "expired", "cust-1", "variant-pro-1",
                null, "2026-07-01T00:00:00Z", false));

        assertThat(existing.getBillingStatus()).isEqualTo("expired");
        assertThat(existing.getStatus()).isEqualTo(SubscriptionState.CANCELLED);
        assertThat(existing.isOpen()).isFalse();
    }

    @Test
    void subscriptionUpdatedChangesStatus() {
        Subscription existing = new Subscription(
                UUID.randomUUID(), SubscriptionPlan.PRO, SubscriptionState.ACTIVE, null, null);
        existing.setLemonsqueezySubscriptionId("ls-sub-1");
        when(repository.findByLemonsqueezySubscriptionId("ls-sub-1"))
                .thenReturn(Optional.of(existing));
        when(repository.save(any(Subscription.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.handleEvent("subscription_updated", webhookPayload(
                "ls-sub-1", "past_due", "cust-1", "variant-pro-1",
                "2026-08-01T00:00:00Z", null, false));

        assertThat(existing.getStatus()).isEqualTo(SubscriptionState.PAST_DUE);
        assertThat(existing.getBillingStatus()).isEqualTo("past_due");
        assertThat(existing.isOpen()).isTrue();
    }

    @Test
    void unknownEventIsIgnored() {
        Map<String, Object> payload = webhookPayload(
                "ls-sub-1", "active", "cust-1", "variant-pro-1",
                "2026-08-01T00:00:00Z", null, false);

        service.handleEvent("some_unknown_event", payload);

        verify(repository, never()).save(any());
    }

    @Test
    void resolveTenantIdDerivesStableUuid() {
        Map<String, Object> meta = Map.of(
                "custom_data", Map.of("subject", "google-oauth2|user123"));

        UUID tenantId = service.resolveTenantId(meta);

        UUID expected = UUID.nameUUIDFromBytes(
                "tenant:google-oauth2|user123".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(tenantId).isEqualTo(expected);
    }

    private static Map<String, Object> webhookPayload(
            String subscriptionId, String status, String customerId,
            String variantId, String renewsAt, String endsAt, boolean cancelled) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("status", status);
        attributes.put("customer_id", customerId);
        attributes.put("variant_id", variantId);
        attributes.put("cancelled", cancelled);
        if (renewsAt != null) {
            attributes.put("renews_at", renewsAt);
        }
        if (endsAt != null) {
            attributes.put("ends_at", endsAt);
        }

        return Map.of(
                "meta", Map.of(
                        "event_name", "subscription_created",
                        "custom_data", Map.of(
                                "subject", "google-oauth2|user123",
                                "email", "user@example.com")),
                "data", Map.of(
                        "type", "subscriptions",
                        "id", subscriptionId,
                        "attributes", attributes));
    }
}
