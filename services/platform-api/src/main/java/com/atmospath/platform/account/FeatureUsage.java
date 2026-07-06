package com.atmospath.platform.account;

public record FeatureUsage(
        MeteredFeature feature,
        String label,
        int used,
        int limit,
        int remaining,
        String resetsAt,
        boolean exceeded) {
    public static FeatureUsage of(MeteredFeature feature, int used, int limit, String resetsAt) {
        var remaining = Math.max(0, limit - used);
        return new FeatureUsage(feature, feature.label(), used, limit, remaining, resetsAt, used >= limit);
    }
}
