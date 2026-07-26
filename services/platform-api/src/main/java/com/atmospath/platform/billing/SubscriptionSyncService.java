package com.atmospath.platform.billing;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import com.atmospath.platform.saas.entity.Subscription;
import com.atmospath.platform.saas.entity.SubscriptionPlan;
import com.atmospath.platform.saas.entity.SubscriptionState;
import com.atmospath.platform.saas.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps Lemon Squeezy webhook event payloads to subscription table rows.
 * Idempotent: processing the same event twice converges to the same state
 * because the {@code lemonsqueezy_subscription_id} acts as a natural key.
 */
public class SubscriptionSyncService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionSyncService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final LemonSqueezyProperties properties;

    public SubscriptionSyncService(SubscriptionRepository subscriptionRepository,
                                   LemonSqueezyProperties properties) {
        this.subscriptionRepository = subscriptionRepository;
        this.properties = properties;
    }

    /**
     * Dispatches a webhook event by its {@code meta.event_name}.
     *
     * @param eventName the Lemon Squeezy event name
     * @param payload   the full parsed webhook JSON
     */
    @SuppressWarnings("unchecked")
    public void handleEvent(String eventName, Map<String, Object> payload) {
        Map<String, Object> meta = (Map<String, Object>) payload.getOrDefault("meta", Map.of());
        Map<String, Object> data = (Map<String, Object>) payload.getOrDefault("data", Map.of());
        Map<String, Object> attributes = (Map<String, Object>) data.getOrDefault("attributes", Map.of());
        String lsSubscriptionId = String.valueOf(data.getOrDefault("id", ""));

        switch (eventName) {
            case "subscription_created" -> upsertSubscription(lsSubscriptionId, attributes, meta, false);
            case "subscription_updated" -> upsertSubscription(lsSubscriptionId, attributes, meta, false);
            case "subscription_cancelled" -> upsertSubscription(lsSubscriptionId, attributes, meta, true);
            case "subscription_expired" -> expireSubscription(lsSubscriptionId, attributes);
            case "order_created" -> handleOrderCreated(data, meta);
            default -> log.info("Ignoring unhandled billing event: {}", eventName);
        }
    }

    @SuppressWarnings("unchecked")
    private void upsertSubscription(String lsSubscriptionId,
                                    Map<String, Object> attributes,
                                    Map<String, Object> meta,
                                    boolean cancelled) {
        String lsStatus = str(attributes.getOrDefault("status", "active"));
        if (cancelled) {
            lsStatus = "cancelled";
        }
        String customerId = str(attributes.getOrDefault("customer_id", ""));
        String variantId = str(attributes.getOrDefault("variant_id", ""));
        Instant renewsAt = parseInstant(attributes.get("renews_at"));
        Instant endsAt = parseInstant(attributes.get("ends_at"));
        boolean cancelAtEnd = Boolean.TRUE.equals(attributes.get("cancelled"))
                || "cancelled".equals(lsStatus);

        UUID tenantId = resolveTenantId(meta);
        SubscriptionPlan plan = resolvePlan(variantId);
        SubscriptionState state = mapStatus(lsStatus);

        Subscription subscription = subscriptionRepository
                .findByLemonsqueezySubscriptionId(lsSubscriptionId)
                .orElseGet(() -> {
                    LocalDate today = LocalDate.now(ZoneOffset.UTC);
                    return new Subscription(tenantId, plan, state, today, null);
                });

        subscription.setLemonsqueezySubscriptionId(lsSubscriptionId);
        subscription.setLemonsqueezyCustomerId(customerId);
        subscription.setLemonsqueezyVariantId(variantId);
        subscription.setBillingStatus(lsStatus);
        subscription.setBillingPeriodEnd(renewsAt != null ? renewsAt : endsAt);
        subscription.setCancelAtPeriodEnd(cancelAtEnd);
        subscription.setPlan(plan);
        subscription.setStatus(state);

        if ("cancelled".equals(lsStatus) || "expired".equals(lsStatus)) {
            subscription.setValidTo(Instant.now());
        } else {
            subscription.setValidTo(Subscription.VALID_FOREVER);
        }

        subscriptionRepository.save(subscription);
        log.info("Synced LS subscription {} for tenant {}: status={}, plan={}",
                lsSubscriptionId, tenantId, lsStatus, plan);
    }

    private void expireSubscription(String lsSubscriptionId, Map<String, Object> attributes) {
        subscriptionRepository.findByLemonsqueezySubscriptionId(lsSubscriptionId)
                .ifPresentOrElse(subscription -> {
                    subscription.setBillingStatus("expired");
                    subscription.setStatus(SubscriptionState.CANCELLED);
                    subscription.setValidTo(Instant.now());
                    subscription.setCancelAtPeriodEnd(false);
                    subscriptionRepository.save(subscription);
                    log.info("Expired LS subscription {} for tenant {}",
                            lsSubscriptionId, subscription.getTenantId());
                }, () -> log.warn("Received subscription_expired for unknown LS subscription {}",
                        lsSubscriptionId));
    }

    @SuppressWarnings("unchecked")
    private void handleOrderCreated(Map<String, Object> data, Map<String, Object> meta) {
        Map<String, Object> attributes = (Map<String, Object>) data.getOrDefault("attributes", Map.of());
        // Orders without a subscription are one-time purchases; log and skip.
        Object subscriptionId = attributes.get("subscription_id");
        if (subscriptionId == null) {
            log.info("Received order_created without subscription_id; skipping");
            return;
        }
        log.info("order_created references subscription {}; will be handled by subscription events",
                subscriptionId);
    }

    /**
     * Derives the tenant ID from the checkout custom_data passed through
     * the webhook meta. Uses the same stable UUID derivation as
     * {@code TenantContextResolver}.
     */
    @SuppressWarnings("unchecked")
    UUID resolveTenantId(Map<String, Object> meta) {
        Map<String, Object> customData = (Map<String, Object>) meta.getOrDefault("custom_data", Map.of());
        String subject = str(customData.getOrDefault("subject", ""));
        if (subject.isBlank()) {
            throw new IllegalArgumentException(
                    "Webhook meta.custom_data.subject is required to resolve tenant");
        }
        return UUID.nameUUIDFromBytes(("tenant:" + subject).getBytes(StandardCharsets.UTF_8));
    }

    SubscriptionPlan resolvePlan(String variantId) {
        if (variantId != null && variantId.equals(properties.proVariantId())) {
            return SubscriptionPlan.PRO;
        }
        // Default: any paid variant maps to PRO until more plans exist.
        return SubscriptionPlan.PRO;
    }

    static SubscriptionState mapStatus(String lsStatus) {
        return switch (lsStatus) {
            case "active" -> SubscriptionState.ACTIVE;
            case "on_trial" -> SubscriptionState.TRIAL;
            case "paused" -> SubscriptionState.GRACE;
            case "past_due" -> SubscriptionState.PAST_DUE;
            case "cancelled", "expired" -> SubscriptionState.CANCELLED;
            default -> SubscriptionState.ACTIVE;
        };
    }

    private static Instant parseInstant(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
