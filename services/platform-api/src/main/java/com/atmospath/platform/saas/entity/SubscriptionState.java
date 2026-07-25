package com.atmospath.platform.saas.entity;

public enum SubscriptionState {
    TRIAL,
    ACTIVE,
    PAST_DUE,
    GRACE,
    CANCELLED;

    /** States that still grant entitlements (quota checks treat these as usable). */
    public boolean grantsEntitlements() {
        return this == TRIAL || this == ACTIVE || this == GRACE;
    }
}
