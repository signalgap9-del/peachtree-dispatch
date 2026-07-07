package com.atmospath.platform.ratelimit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atmospath.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        Duration window,
        int publicReadLimitPerMinute,
        int mutationLimitPerMinute,
        int authenticatedMutationLimitPerMinute) {
    public RateLimitProperties {
        if (window == null || window.isNegative() || window.isZero()) {
            window = Duration.ofMinutes(1);
        }
        publicReadLimitPerMinute = Math.max(1, publicReadLimitPerMinute);
        mutationLimitPerMinute = Math.max(1, mutationLimitPerMinute);
        authenticatedMutationLimitPerMinute = Math.max(1, authenticatedMutationLimitPerMinute);
    }
}
