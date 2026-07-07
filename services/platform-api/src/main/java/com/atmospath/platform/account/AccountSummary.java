package com.atmospath.platform.account;

import java.util.List;
import java.util.UUID;

public record AccountSummary(
        UserProfile user,
        WorkspaceSummary workspace,
        PlanSummary plan,
        List<FeatureUsage> dailyUsage,
        CapacityUsage savedRoutes,
        CapacityUsage savedPlaces,
        List<ReadinessSignal> readiness) {
    public record UserProfile(UUID userId, String subject, String email) {
    }

    public record WorkspaceSummary(UUID tenantId, String name, TenantRole role) {
    }

    public record PlanSummary(
            PlanCode code,
            SubscriptionStatus status,
            int savedRouteHistoryDays,
            boolean dispatchOptimizerEnabled,
            boolean teamWorkspaceEnabled) {
    }

    public record ReadinessSignal(String key, String label, String state, String detail) {
    }
}
