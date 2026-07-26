package com.atmospath.platform.compliance.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.atmospath.platform.saas.entity.Subscription;

/**
 * Subscription projection for the GDPR data export. Keeps the pseudonymous
 * Lemon Squeezy identifiers (merchant-of-record billing references) and
 * never any card data, per docs/data-retention.md section 1.
 */
public record SubscriptionExport(
        String plan,
        String status,
        LocalDate currentPeriodStart,
        LocalDate currentPeriodEnd,
        String billingStatus,
        Instant billingPeriodEnd,
        String lemonsqueezySubscriptionId,
        String lemonsqueezyCustomerId,
        Instant createdAt) {

    public static SubscriptionExport from(Subscription entity) {
        return new SubscriptionExport(
                entity.getPlan() == null ? null : entity.getPlan().name(),
                entity.getStatus() == null ? null : entity.getStatus().name(),
                entity.getCurrentPeriodStart(),
                entity.getCurrentPeriodEnd(),
                entity.getBillingStatus(),
                entity.getBillingPeriodEnd(),
                entity.getLemonsqueezySubscriptionId(),
                entity.getLemonsqueezyCustomerId(),
                entity.getCreatedAt());
    }
}
