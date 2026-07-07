package com.atmospath.platform.account;

public enum TenantRole {
    OWNER,
    ADMIN,
    MEMBER,
    VIEWER;

    public boolean canManageSavedAssets() {
        return this == OWNER || this == ADMIN || this == MEMBER;
    }
}
