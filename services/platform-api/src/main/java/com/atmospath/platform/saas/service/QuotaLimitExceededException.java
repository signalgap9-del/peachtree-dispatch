package com.atmospath.platform.saas.service;

import java.util.UUID;

public class QuotaLimitExceededException extends RuntimeException {

    private final transient UUID tenantId;
    private final String feature;
    private final long limit;

    public QuotaLimitExceededException(UUID tenantId, String feature, long limit) {
        super("Quota exhausted for tenant " + tenantId + " feature " + feature + " (limit " + limit + ")");
        this.tenantId = tenantId;
        this.feature = feature;
        this.limit = limit;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getFeature() {
        return feature;
    }

    public long getLimit() {
        return limit;
    }
}
