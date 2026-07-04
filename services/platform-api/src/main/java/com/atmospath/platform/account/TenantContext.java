package com.atmospath.platform.account;

import java.util.UUID;

public record TenantContext(
        UUID tenantId,
        UUID userId,
        String subject,
        String email,
        PlanCode plan,
        SubscriptionStatus status,
        String role,
        boolean authenticated) {
}
