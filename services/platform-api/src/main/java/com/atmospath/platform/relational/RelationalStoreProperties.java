package com.atmospath.platform.relational;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("atmospath.relational")
public record RelationalStoreProperties(
        boolean enabled,
        boolean initializeSchema,
        String database,
        String resourceArn,
        String secretArn) {
}
