package com.atmospath.platform.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atmospath.billing")
public record LemonSqueezyProperties(
        boolean enabled,
        String provider,
        String apiBaseUrl,
        String apiKey,
        String storeId,
        String webhookSecret,
        String proVariantId) {

    public LemonSqueezyProperties {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            apiBaseUrl = "https://api.lemonsqueezy.com/v1";
        }
    }
}
