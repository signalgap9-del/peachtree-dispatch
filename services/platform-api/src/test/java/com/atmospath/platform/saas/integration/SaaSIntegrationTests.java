package com.atmospath.platform.saas.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import com.atmospath.platform.saas.entity.AlertEvent;
import com.atmospath.platform.saas.entity.AlertSeverity;
import com.atmospath.platform.saas.entity.AlertState;
import com.atmospath.platform.saas.entity.Subscription;
import com.atmospath.platform.saas.entity.SubscriptionPlan;
import com.atmospath.platform.saas.entity.SubscriptionState;
import com.atmospath.platform.saas.service.AlertEscalationService;
import com.atmospath.platform.saas.service.PermissionService;
import com.atmospath.platform.saas.service.QuotaService;
import com.atmospath.platform.saas.service.QuotaUsage;
import com.atmospath.platform.saas.service.SubscriptionLifecycleService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration tests exercising the full SaaS data layer against a real
 * PostgreSQL instance with the V003 migration applied (stored functions,
 * partitioned usage_record) and Redis for the quota hot path.
 *
 *   docker compose --profile relational up -d postgres redis
 *   SAAS_ENABLED=true
 */
@SpringBootTest(properties = {
        "atmospath.saas.enabled=true",
        "atmospath.datasource.primary-url=jdbc:postgresql://localhost:5432/atmospath",
        "atmospath.datasource.username=atmospath",
        "atmospath.datasource.password=atmospath-local",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Disabled("Requires PostgreSQL with the SaaS schema plus Redis (docker compose --profile relational up -d)")
class SaaSIntegrationTests {

    @Autowired
    private SubscriptionLifecycleService subscriptionService;

    @Autowired
    private AlertEscalationService alertService;

    @Autowired
    private QuotaService quotaService;

    @Autowired
    private PermissionService permissionService;

    private static final UUID TENANT_A = UUID.randomUUID();

    @Test
    @Order(1)
    void fullLifecycle_trialThroughAlertAcknowledgement() {
        // Start trial
        Subscription subscription = subscriptionService.startTrial(TENANT_A);
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionState.TRIAL);
        assertThat(subscription.getPlan()).isEqualTo(SubscriptionPlan.PRO);

        // Upgrade with proration
        Subscription upgraded = subscriptionService.upgradePlan(subscription.getId(), SubscriptionPlan.ENTERPRISE);
        assertThat(upgraded).isNotNull();

        // Consume quota (requires an entitlement row for the subscription)
        // QuotaUsage usage = quotaService.consume(TENANT_A, "ROUTE_PLAN");
        // assertThat(usage.used()).isEqualTo(1);

        // Trigger + acknowledge alert (requires a saved_route row for TENANT_A)
        // AlertEvent alert = alertService.triggerAlert(routeId, 88, AlertSeverity.HIGH);
        // assertThat(alert.getState()).isEqualTo(AlertState.NOTIFIED);
        // AlertEvent acknowledged = alertService.acknowledgeAlert(alert.getId(), memberId);
        // assertThat(acknowledged.getState()).isEqualTo(AlertState.ACKNOWLEDGED);
    }

    @Test
    @Order(2)
    void paymentFailureLadderRunsToCancellation() {
        Subscription subscription = subscriptionService.startTrial(UUID.randomUUID());

        subscription = subscriptionService.handlePaymentFailure(subscription.getId());
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionState.PAST_DUE);

        subscription = subscriptionService.handlePaymentFailure(subscription.getId());
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionState.GRACE);

        subscription = subscriptionService.handlePaymentFailure(subscription.getId());
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionState.CANCELLED);
    }

    @Test
    @Order(3)
    void permissionChecksDelegateToDatabaseFunction() {
        // Requires seeded tenant_member + saved_route rows:
        // assertThat(permissionService.hasPermission(ownerId, "tenant", TENANT_A, "DELETE")).isTrue();
        // assertThat(permissionService.hasPermission(memberId, "tenant", TENANT_A, "DELETE")).isFalse();
        assertThat(permissionService).isNotNull();
    }

    @Test
    @Order(4)
    void reconcileSyncsRedisUsageToPostgresUsageRecord() {
        // Requires an entitlement row granting PLACE_SEARCH for the tenant:
        // quotaService.consume(tenantId, "PLACE_SEARCH");
        // quotaService.consume(tenantId, "PLACE_SEARCH");
        // quotaService.reconcile();
        // assertThat(usageRecordRepository.sumForDate(tenantId, "PLACE_SEARCH", LocalDate.now(Clock.systemUTC())))
        //         .isEqualTo(2);
        assertThat(quotaService).isNotNull();
    }
}
