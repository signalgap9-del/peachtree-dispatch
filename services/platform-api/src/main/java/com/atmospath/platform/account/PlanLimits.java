package com.atmospath.platform.account;

public record PlanLimits(
        PlanCode plan,
        int routePlansPerDay,
        int placeSearchesPerDay,
        int locationRiskChecksPerDay,
        int alertSearchesPerDay,
        int savedRoutes,
        int savedPlaces,
        int savedRouteHistoryDays,
        boolean dispatchOptimizerEnabled,
        boolean teamWorkspaceEnabled) {
    public static PlanLimits forPlan(PlanCode plan) {
        return switch (plan) {
            case FREE -> new PlanLimits(plan, 30, 100, 120, 150, 10, 25, 7, false, false);
            case PRO -> new PlanLimits(plan, 300, 1_500, 2_000, 2_000, 100, 250, 30, true, false);
            case TEAM -> new PlanLimits(plan, 2_000, 10_000, 12_000, 12_000, 1_000, 2_500, 90, true, true);
            case INTERNAL -> new PlanLimits(plan, 100_000, 500_000, 500_000, 500_000, 100_000, 100_000, 365, true, true);
        };
    }

    public int limitFor(MeteredFeature feature) {
        return switch (feature) {
            case ROUTE_PLAN -> routePlansPerDay;
            case PLACE_SEARCH -> placeSearchesPerDay;
            case LOCATION_RISK -> locationRiskChecksPerDay;
            case ALERT_SEARCH -> alertSearchesPerDay;
            case SAVED_ROUTE -> savedRoutes;
            case SAVED_PLACE -> savedPlaces;
        };
    }
}
