package com.atmospath.platform.billing;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.atmospath.platform.billing.dto.CheckoutRequest;
import com.atmospath.platform.billing.dto.CheckoutResponse;
import com.atmospath.platform.billing.dto.PortalResponse;
import com.atmospath.platform.billing.dto.SubscriptionResponse;
import com.atmospath.platform.saas.entity.Subscription;
import com.atmospath.platform.saas.repository.SubscriptionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authenticated billing endpoints: checkout creation, subscription status,
 * and customer portal access.
 */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private final LemonSqueezyClient client;
    private final LemonSqueezyProperties properties;
    private final SubscriptionRepository subscriptionRepository;

    public BillingController(LemonSqueezyClient client,
                             LemonSqueezyProperties properties,
                             SubscriptionRepository subscriptionRepository) {
        this.client = client;
        this.properties = properties;
        this.subscriptionRepository = subscriptionRepository;
    }

    @PostMapping("/checkout")
    @SuppressWarnings("unchecked")
    public CheckoutResponse createCheckout(@AuthenticationPrincipal Jwt jwt,
                                           @RequestBody(required = false) CheckoutRequest request) {
        requireAuth(jwt);
        String variantId = (request != null && request.variantId() != null && !request.variantId().isBlank())
                ? request.variantId()
                : properties.proVariantId();
        if (variantId == null || variantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No variant ID provided and no default configured");
        }

        Map<String, String> customData = Map.of(
                "subject", jwt.getSubject(),
                "email", safe(jwt.getClaimAsString("email")));

        Map<String, Object> response = client.createCheckout(
                properties.storeId(), variantId, customData);

        Map<String, Object> data = (Map<String, Object>) response.getOrDefault("data", Map.of());
        Map<String, Object> attributes = (Map<String, Object>) data.getOrDefault("attributes", Map.of());
        String checkoutUrl = String.valueOf(attributes.getOrDefault("url", ""));

        return new CheckoutResponse(checkoutUrl);
    }

    @GetMapping("/subscription")
    public SubscriptionResponse getSubscription(@AuthenticationPrincipal Jwt jwt) {
        requireAuth(jwt);
        UUID tenantId = tenantId(jwt);

        return subscriptionRepository.findActiveByTenantId(tenantId)
                .map(this::toResponse)
                .orElse(new SubscriptionResponse("free", "none", null, false, null));
    }

    @PostMapping("/portal")
    @SuppressWarnings("unchecked")
    public PortalResponse getPortal(@AuthenticationPrincipal Jwt jwt) {
        requireAuth(jwt);
        UUID tenantId = tenantId(jwt);

        Subscription subscription = subscriptionRepository.findActiveByTenantId(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No active subscription found"));

        String lsSubscriptionId = subscription.getLemonsqueezySubscriptionId();
        if (lsSubscriptionId == null || lsSubscriptionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Subscription is not managed by Lemon Squeezy");
        }

        Map<String, Object> response = client.getSubscription(lsSubscriptionId);
        Map<String, Object> data = (Map<String, Object>) response.getOrDefault("data", Map.of());
        Map<String, Object> attributes = (Map<String, Object>) data.getOrDefault("attributes", Map.of());
        Map<String, Object> urls = (Map<String, Object>) attributes.getOrDefault("urls", Map.of());
        String portalUrl = String.valueOf(
                urls.getOrDefault("customer_portal",
                        urls.getOrDefault("update_payment_method", "")));

        return new PortalResponse(portalUrl);
    }

    private SubscriptionResponse toResponse(Subscription subscription) {
        String plan = subscription.getPlan() != null
                ? subscription.getPlan().name().toLowerCase()
                : "free";
        String status = subscription.getBillingStatus() != null
                ? subscription.getBillingStatus()
                : subscription.getStatus().name().toLowerCase();
        Instant periodEnd = subscription.getBillingPeriodEnd();
        boolean cancelAtEnd = Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd());
        return new SubscriptionResponse(plan, status, periodEnd, cancelAtEnd, null);
    }

    private static void requireAuth(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
    }

    private static UUID tenantId(Jwt jwt) {
        return UUID.nameUUIDFromBytes(
                ("tenant:" + jwt.getSubject()).getBytes(StandardCharsets.UTF_8));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
