package com.atmospath.platform.llm.proactive;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the proactive risk intelligence subsystem.
 * All beans in the {@code proactive} package are conditional on
 * {@code atmospath.llm.proactive.enabled=true}.
 */
@ConfigurationProperties(prefix = "atmospath.llm.proactive")
public record ProactiveProperties(
        boolean enabled,
        long checkIntervalMs,
        int dedupWindowMinutes,
        int maxSuggestionsPerTenant) {

    public ProactiveProperties {
        if (checkIntervalMs <= 0) {
            checkIntervalMs = 300_000L;
        }
        if (dedupWindowMinutes <= 0) {
            dedupWindowMinutes = 60;
        }
        if (maxSuggestionsPerTenant <= 0) {
            maxSuggestionsPerTenant = 10;
        }
    }
}
