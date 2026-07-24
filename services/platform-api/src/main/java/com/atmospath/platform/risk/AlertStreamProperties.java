package com.atmospath.platform.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atmospath.alert-stream")
public record AlertStreamProperties(
        boolean enabled,
        long pollIntervalMs,
        long heartbeatIntervalMs,
        long emitterTimeoutMs) {
    public AlertStreamProperties {
        pollIntervalMs = Math.max(1_000L, pollIntervalMs);
        heartbeatIntervalMs = Math.max(1_000L, heartbeatIntervalMs);
        emitterTimeoutMs = Math.max(10_000L, emitterTimeoutMs);
    }
}
