package com.atmospath.platform.account;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class EntitlementService {
    private static final List<MeteredFeature> DAILY_FEATURES = List.of(
            MeteredFeature.ROUTE_PLAN,
            MeteredFeature.PLACE_SEARCH,
            MeteredFeature.LOCATION_RISK,
            MeteredFeature.ALERT_SEARCH);

    private final UsageRepository usageRepository;
    private final Clock clock;

    @Autowired
    public EntitlementService(UsageRepository usageRepository) {
        this(usageRepository, Clock.systemUTC());
    }

    EntitlementService(UsageRepository usageRepository, Clock clock) {
        this.usageRepository = usageRepository;
        this.clock = clock;
    }

    public FeatureUsage consume(TenantContext context, MeteredFeature feature) {
        var limits = PlanLimits.forPlan(context.plan());
        var limit = limits.limitFor(feature);
        var today = today();
        var used = usageRepository.incrementAndGet(context.tenantId(), feature, today);
        if (used > limit) {
            throw new QuotaExceededException(feature, context.plan(), used, limit, resetInstant());
        }
        return FeatureUsage.of(feature, used, limit, resetInstant());
    }

    public void requireSavedRouteCapacity(TenantContext context, int currentSavedRoutes) {
        requireCapacity(context, MeteredFeature.SAVED_ROUTE, currentSavedRoutes);
    }

    public void requireSavedPlaceCapacity(TenantContext context, int currentSavedPlaces) {
        requireCapacity(context, MeteredFeature.SAVED_PLACE, currentSavedPlaces);
    }

    public AccountSummary summary(TenantContext context, int savedRouteCount, int savedPlaceCount) {
        var limits = PlanLimits.forPlan(context.plan());
        return new AccountSummary(
                new AccountSummary.UserProfile(context.userId(), context.subject(), context.email()),
                new AccountSummary.WorkspaceSummary(context.tenantId(), "Personal workspace", context.role()),
                new AccountSummary.PlanSummary(context.plan(), context.status(), limits.savedRouteHistoryDays(),
                        limits.dispatchOptimizerEnabled(), limits.teamWorkspaceEnabled()),
                DAILY_FEATURES.stream()
                        .map(feature -> FeatureUsage.of(feature, usageRepository.current(context.tenantId(), feature, today()),
                                limits.limitFor(feature), resetInstant()))
                        .toList(),
                CapacityUsage.of(MeteredFeature.SAVED_ROUTE, savedRouteCount, limits.savedRoutes()),
                CapacityUsage.of(MeteredFeature.SAVED_PLACE, savedPlaceCount, limits.savedPlaces()),
                readiness(context));
    }

    private void requireCapacity(TenantContext context, MeteredFeature feature, int currentCount) {
        var limit = PlanLimits.forPlan(context.plan()).limitFor(feature);
        if (currentCount >= limit) {
            throw new QuotaExceededException(feature, context.plan(), currentCount, limit, resetInstant());
        }
    }

    private List<AccountSummary.ReadinessSignal> readiness(TenantContext context) {
        return List.of(
                new AccountSummary.ReadinessSignal("auth", "Google OAuth / Cognito boundary",
                        context.authenticated() ? "READY" : "PUBLIC_PREVIEW", context.authenticated()
                        ? "Requests are scoped to the signed-in user subject."
                        : "Anonymous requests are allowed for map preview and are quota-limited."),
                new AccountSummary.ReadinessSignal("quota", "Plan and usage limits", "ENFORCED",
                        "Route planning, place search, location risk, saved route, and saved place limits are checked server-side."),
                new AccountSummary.ReadinessSignal("origin", "CloudFront origin verification", "CONFIGURABLE",
                        "Set API_ORIGIN_VERIFY_SECRET to reject direct origin traffic in deployed environments."));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    private String resetInstant() {
        return today().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
