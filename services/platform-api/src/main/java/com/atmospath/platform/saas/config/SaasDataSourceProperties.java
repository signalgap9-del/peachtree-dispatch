package com.atmospath.platform.saas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the SaaS relational stack. The primary URL points
 * at PgBouncer by default; {@code prepareThreshold=0} keeps Hibernate
 * compatible with transaction-mode pooling. Leave {@code replicaUrl} blank
 * to route all reads to the primary.
 */
@ConfigurationProperties(prefix = "atmospath.datasource")
public record SaasDataSourceProperties(
        String primaryUrl,
        String replicaUrl,
        String username,
        String password,
        int maxPoolSize,
        long connectionTimeoutMs) {

    public SaasDataSourceProperties {
        if (primaryUrl == null || primaryUrl.isBlank()) {
            primaryUrl = "jdbc:postgresql://localhost:6432/atmospath?prepareThreshold=0";
        }
        if (username == null || username.isBlank()) {
            username = "atmospath";
        }
        if (password == null) {
            password = "";
        }
        if (maxPoolSize <= 0) {
            maxPoolSize = 20;
        }
        if (connectionTimeoutMs <= 0) {
            connectionTimeoutMs = 5_000L;
        }
    }

    public boolean hasReplica() {
        return replicaUrl != null && !replicaUrl.isBlank();
    }
}
