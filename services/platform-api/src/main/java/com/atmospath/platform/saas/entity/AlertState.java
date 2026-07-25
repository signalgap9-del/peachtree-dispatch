package com.atmospath.platform.saas.entity;

public enum AlertState {
    TRIGGERED,
    NOTIFIED,
    ESCALATED,
    ACKNOWLEDGED,
    RESOLVED;

    public boolean closed() {
        return this == ACKNOWLEDGED || this == RESOLVED;
    }
}
