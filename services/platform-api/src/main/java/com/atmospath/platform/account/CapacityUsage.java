package com.atmospath.platform.account;

public record CapacityUsage(
        MeteredFeature feature,
        String label,
        int used,
        int limit,
        int remaining,
        boolean exceeded) {
    public static CapacityUsage of(MeteredFeature feature, int used, int limit) {
        return new CapacityUsage(feature, feature.label(), used, limit, Math.max(0, limit - used), used >= limit);
    }
}
