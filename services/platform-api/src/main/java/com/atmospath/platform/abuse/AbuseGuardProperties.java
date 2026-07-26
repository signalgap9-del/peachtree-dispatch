package com.atmospath.platform.abuse;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the application-level anti-abuse / financial protection
 * layer. All values are env-driven so operators can tune caps without a
 * redeploy.
 */
@ConfigurationProperties(prefix = "atmospath.abuse")
public record AbuseGuardProperties(
        boolean enabled,
        double llmDailyCostCapUsd,
        double llmCostPer1kInputTokens,
        double llmCostPer1kOutputTokens,
        int llmMaxInputChars,
        int llmRatePerMinuteAuth,
        int llmRatePerMinuteAnon,
        long halfOpenProbeIntervalMs) {

    public AbuseGuardProperties {
        if (llmDailyCostCapUsd <= 0) {
            llmDailyCostCapUsd = 5.0;
        }
        if (llmCostPer1kInputTokens <= 0) {
            llmCostPer1kInputTokens = 0.00014;
        }
        if (llmCostPer1kOutputTokens <= 0) {
            llmCostPer1kOutputTokens = 0.00028;
        }
        if (llmMaxInputChars <= 0) {
            llmMaxInputChars = 4000;
        }
        if (llmRatePerMinuteAuth < 0) {
            llmRatePerMinuteAuth = 20;
        }
        if (llmRatePerMinuteAnon < 0) {
            llmRatePerMinuteAnon = 0;
        }
        if (halfOpenProbeIntervalMs <= 0) {
            halfOpenProbeIntervalMs = 60_000L;
        }
    }

    /** Estimated cost in USD for the given token counts. */
    public double estimateCostUsd(int inputTokens, int outputTokens) {
        return (inputTokens / 1000.0) * llmCostPer1kInputTokens
             + (outputTokens / 1000.0) * llmCostPer1kOutputTokens;
    }
}
