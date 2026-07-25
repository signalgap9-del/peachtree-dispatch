package com.atmospath.platform.saas.service;

import java.time.LocalDate;
import java.util.UUID;

/** Snapshot of a tenant's daily feature usage against its entitlement. */
public record QuotaUsage(
        UUID tenantId,
        String feature,
        long used,
        long limit,
        LocalDate usageDate) {

    public long remaining() {
        return Math.max(0L, limit - used);
    }

    public boolean exhausted() {
        return used >= limit;
    }
}
