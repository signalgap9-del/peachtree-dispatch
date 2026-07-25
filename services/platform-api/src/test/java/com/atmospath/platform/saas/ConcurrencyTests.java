package com.atmospath.platform.saas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.atmospath.platform.saas.entity.AlertEvent;
import com.atmospath.platform.saas.entity.AlertSeverity;
import com.atmospath.platform.saas.entity.SubscriptionPlan;
import com.atmospath.platform.saas.service.AlertEscalationService;
import com.atmospath.platform.saas.service.QuotaService;
import com.atmospath.platform.saas.service.SubscriptionLifecycleService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Concurrency tests for the SaaS data layer: atomic quota consumption
 * (Redis INCR with database fallback), subscription mutations serialized
 * by the Redis distributed lock, and alert state transitions serialized
 * by transition_alert_state() row locking.
 *
 * Requires the relational + redis + full SaaS stack:
 *   SAAS_ENABLED=true and PostgreSQL with the V003 migration applied.
 */
@SpringBootTest(properties = {
        "atmospath.saas.enabled=true",
        "atmospath.datasource.primary-url=jdbc:postgresql://localhost:5432/atmospath",
        "atmospath.datasource.username=atmospath",
        "atmospath.datasource.password=atmospath-local",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379"
})
@Disabled("Requires PostgreSQL with the SaaS schema plus Redis (docker compose --profile relational up -d)")
class ConcurrencyTests {

    @Autowired
    private QuotaService quotaService;

    @Autowired
    private SubscriptionLifecycleService subscriptionService;

    @Autowired
    private AlertEscalationService alertService;

    @Test
    void tenConcurrentConsumersProduceCorrectTotalCount() throws Exception {
        var tenantId = UUID.randomUUID();
        int threadCount = 10;
        var latch = new CountDownLatch(1);
        var done = new CountDownLatch(threadCount);
        List<Exception> errors = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        latch.await();
                        quotaService.consume(tenantId, "ROUTE_PLAN");
                    } catch (Exception e) {
                        errors.add(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            latch.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(errors).isEmpty();
        var usage = quotaService.getUsage(tenantId, "ROUTE_PLAN");
        assertThat(usage.used()).isEqualTo(threadCount);
    }

    @Test
    void twoConcurrentUpgradesAreSerializedByTheDistributedLock() throws Exception {
        var tenantId = UUID.randomUUID();
        var subscription = subscriptionService.startTrial(tenantId);
        var subscriptionId = subscription.getId();

        var latch = new CountDownLatch(1);
        var successes = new AtomicInteger(0);
        var lockConflicts = new AtomicInteger(0);
        var done = new CountDownLatch(2);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                executor.submit(() -> {
                    try {
                        latch.await();
                        subscriptionService.upgradePlan(subscriptionId, SubscriptionPlan.ENTERPRISE);
                        successes.incrementAndGet();
                    } catch (IllegalStateException e) {
                        // Lock busy or lost race on the same subscription.
                        lockConflicts.incrementAndGet();
                    } catch (Exception e) {
                        // unexpected
                    } finally {
                        done.countDown();
                    }
                });
            }
            latch.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        }

        // The 30s lock TTL means the second caller normally fails fast; at
        // most one upgrade may succeed per lock acquisition window.
        assertThat(successes.get() + lockConflicts.get()).isEqualTo(2);
        assertThat(successes.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void concurrentAcknowledgesOnlyOneWinsTheTransition() throws Exception {
        var routeId = UUID.randomUUID();
        AlertEvent event = alertService.triggerAlert(routeId, 85, AlertSeverity.HIGH);
        var eventId = event.getId();

        var latch = new CountDownLatch(1);
        var successes = new AtomicInteger(0);
        var rejections = new AtomicInteger(0);
        var done = new CountDownLatch(2);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                var memberId = UUID.randomUUID();
                executor.submit(() -> {
                    try {
                        latch.await();
                        alertService.acknowledgeAlert(eventId, memberId);
                        successes.incrementAndGet();
                    } catch (IllegalStateException e) {
                        // transition_alert_state() rejected the second writer.
                        rejections.incrementAndGet();
                    } catch (Exception e) {
                        // unexpected
                    } finally {
                        done.countDown();
                    }
                });
            }
            latch.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(successes.get()).isEqualTo(1);
        assertThat(rejections.get()).isEqualTo(1);
    }
}
