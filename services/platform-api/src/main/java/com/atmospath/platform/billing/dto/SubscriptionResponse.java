package com.atmospath.platform.billing.dto;

import java.time.Instant;

public record SubscriptionResponse(
        String plan,
        String status,
        Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd,
        String manageUrl) {
}
