package com.atmospath.platform.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class EntitlementServiceTests {
    private final InMemoryUsageRepository usageRepository = new InMemoryUsageRepository();
    private final EntitlementService service = new EntitlementService(
            usageRepository,
            Clock.fixed(Instant.parse("2026-07-04T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void consumesDailyRoutePlanQuotaAndReportsRemaining() {
        var context = tenant(PlanCode.FREE);

        var usage = service.consume(context, MeteredFeature.ROUTE_PLAN);

        assertThat(usage.used()).isEqualTo(1);
        assertThat(usage.limit()).isEqualTo(30);
        assertThat(usage.remaining()).isEqualTo(29);
        assertThat(usage.resetsAt()).isEqualTo("2026-07-05T00:00:00Z");
    }

    @Test
    void rejectsRequestsAfterPlanLimitIsExceeded() {
        var context = tenant(PlanCode.FREE);
        for (var i = 0; i < 30; i++) {
            service.consume(context, MeteredFeature.ROUTE_PLAN);
        }

        assertThatThrownBy(() -> service.consume(context, MeteredFeature.ROUTE_PLAN))
                .isInstanceOf(QuotaExceededException.class)
                .satisfies(exception -> {
                    var quota = (QuotaExceededException) exception;
                    assertThat(quota.feature()).isEqualTo(MeteredFeature.ROUTE_PLAN);
                    assertThat(quota.used()).isEqualTo(31);
                    assertThat(quota.limit()).isEqualTo(30);
                    assertThat(quota.plan()).isEqualTo(PlanCode.FREE);
                });
    }

    @Test
    void accountSummaryCombinesDailyUsageAndPersistentCapacity() {
        var context = tenant(PlanCode.PRO);
        service.consume(context, MeteredFeature.PLACE_SEARCH);
        service.consume(context, MeteredFeature.LOCATION_RISK);

        var summary = service.summary(context, 4, 9);

        assertThat(summary.plan().code()).isEqualTo(PlanCode.PRO);
        assertThat(summary.savedRoutes().used()).isEqualTo(4);
        assertThat(summary.savedRoutes().limit()).isEqualTo(100);
        assertThat(summary.savedPlaces().used()).isEqualTo(9);
        assertThat(summary.dailyUsage()).extracting(FeatureUsage::feature)
                .containsExactly(MeteredFeature.ROUTE_PLAN, MeteredFeature.PLACE_SEARCH,
                        MeteredFeature.LOCATION_RISK, MeteredFeature.ALERT_SEARCH);
        assertThat(summary.readiness()).extracting(AccountSummary.ReadinessSignal::key)
                .contains("auth", "quota", "origin");
    }

    @Test
    void savedRouteCapacityUsesPlanLimit() {
        var context = tenant(PlanCode.FREE);

        assertThatThrownBy(() -> service.requireSavedRouteCapacity(context, 10))
                .isInstanceOf(QuotaExceededException.class)
                .satisfies(exception -> assertThat(((QuotaExceededException) exception).feature())
                        .isEqualTo(MeteredFeature.SAVED_ROUTE));
    }

    private static TenantContext tenant(PlanCode plan) {
        return new TenantContext(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "google-oauth2|user",
                "user@example.com",
                plan,
                SubscriptionStatus.ACTIVE,
                TenantRole.OWNER,
                true);
    }
}
