package com.atmospath.platform.saas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import com.atmospath.platform.saas.entity.Subscription;
import com.atmospath.platform.saas.entity.SubscriptionPlan;
import com.atmospath.platform.saas.entity.SubscriptionState;
import com.atmospath.platform.saas.redis.RedisDistributedLock;
import com.atmospath.platform.saas.redis.RedisDistributedLock.LockHandle;
import com.atmospath.platform.saas.repository.AuditLogRepository;
import com.atmospath.platform.saas.repository.SubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class SubscriptionLifecycleServiceTests {

    private static final UUID TENANT = UUID.randomUUID();

    private SubscriptionRepository subscriptionRepo;
    private AuditLogRepository auditLogRepo;
    private RedisDistributedLock distributedLock;
    private SubscriptionLifecycleService service;

    @BeforeEach
    void setUp() {
        subscriptionRepo = mock(SubscriptionRepository.class);
        auditLogRepo = mock(AuditLogRepository.class);
        distributedLock = mock(RedisDistributedLock.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

        // Make TransactionTemplate execute the callback immediately
        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(txManager.getTransaction(any())).thenReturn(txStatus);

        service = new SubscriptionLifecycleService(
                subscriptionRepo, auditLogRepo, distributedLock, txManager, new ObjectMapper());

        // Default: lock is available
        when(distributedLock.tryLock(anyString(), any(Duration.class)))
                .thenReturn(Optional.of(new LockHandle("key", "token")));
        when(distributedLock.unlock(any())).thenReturn(true);
    }

    @Test
    void startTrialCreatesTrialSubscription() {
        when(subscriptionRepo.findActiveByTenantId(TENANT)).thenReturn(Optional.empty());
        when(subscriptionRepo.save(any(Subscription.class))).thenAnswer(inv -> inv.getArgument(0));

        Subscription sub = service.startTrial(TENANT);

        assertThat(sub.getTenantId()).isEqualTo(TENANT);
        assertThat(sub.getPlan()).isEqualTo(SubscriptionPlan.PRO);
        assertThat(sub.getStatus()).isEqualTo(SubscriptionState.TRIAL);
        verify(auditLogRepo).save(any());
    }

    @Test
    void startTrialRejectsWhenActiveSubscriptionExists() {
        Subscription existing = new Subscription(TENANT, SubscriptionPlan.PRO,
                SubscriptionState.ACTIVE, null, null);
        when(subscriptionRepo.findActiveByTenantId(TENANT)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.startTrial(TENANT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already has an active subscription");
    }

    @Test
    void handlePaymentFailureAdvancesDunningLadder() {
        UUID subId = UUID.randomUUID();
        Subscription sub = new Subscription(TENANT, SubscriptionPlan.PRO,
                SubscriptionState.ACTIVE, null, null);
        when(subscriptionRepo.findById(subId)).thenReturn(Optional.of(sub));

        // ACTIVE -> PAST_DUE
        Subscription result = service.handlePaymentFailure(subId);
        assertThat(result.getStatus()).isEqualTo(SubscriptionState.PAST_DUE);

        // PAST_DUE -> GRACE
        result = service.handlePaymentFailure(subId);
        assertThat(result.getStatus()).isEqualTo(SubscriptionState.GRACE);

        // GRACE -> CANCELLED
        result = service.handlePaymentFailure(subId);
        assertThat(result.getStatus()).isEqualTo(SubscriptionState.CANCELLED);
    }

    @Test
    void handlePaymentFailureOnCancelledIsNoOp() {
        UUID subId = UUID.randomUUID();
        Subscription sub = new Subscription(TENANT, SubscriptionPlan.PRO,
                SubscriptionState.CANCELLED, null, null);
        when(subscriptionRepo.findById(subId)).thenReturn(Optional.of(sub));

        Subscription result = service.handlePaymentFailure(subId);
        assertThat(result.getStatus()).isEqualTo(SubscriptionState.CANCELLED);
    }

    @Test
    void renewSubscriptionExtendsPeriod() {
        UUID subId = UUID.randomUUID();
        LocalDate periodEnd = LocalDate.of(2026, 7, 1);
        Subscription sub = new Subscription(TENANT, SubscriptionPlan.PRO,
                SubscriptionState.PAST_DUE, null, periodEnd);
        when(subscriptionRepo.findById(subId)).thenReturn(Optional.of(sub));

        Subscription result = service.renewSubscription(subId);

        assertThat(result.getStatus()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(result.getCurrentPeriodEnd()).isEqualTo(periodEnd.plusDays(30));
        verify(auditLogRepo).save(any());
    }

    @Test
    void renewCancelledSubscriptionThrows() {
        UUID subId = UUID.randomUUID();
        Subscription sub = new Subscription(TENANT, SubscriptionPlan.PRO,
                SubscriptionState.CANCELLED, null, null);
        when(subscriptionRepo.findById(subId)).thenReturn(Optional.of(sub));

        assertThatThrownBy(() -> service.renewSubscription(subId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cancelled");
    }

    @Test
    void upgradePlanProratesAndReturnsNewVersion() {
        UUID subId = UUID.randomUUID();
        UUID newSubId = UUID.randomUUID();
        Subscription current = new Subscription(TENANT, SubscriptionPlan.STARTER,
                SubscriptionState.ACTIVE, null, null);
        Subscription upgraded = new Subscription(TENANT, SubscriptionPlan.PRO,
                SubscriptionState.ACTIVE, null, null);

        when(subscriptionRepo.findById(subId)).thenReturn(Optional.of(current));
        when(subscriptionRepo.prorateSubscription(subId, "PRO"))
                .thenReturn("{\"new_subscription_id\":\"" + newSubId + "\"}");
        when(subscriptionRepo.findById(newSubId)).thenReturn(Optional.of(upgraded));

        Subscription result = service.upgradePlan(subId, SubscriptionPlan.PRO);

        assertThat(result.getPlan()).isEqualTo(SubscriptionPlan.PRO);
        verify(auditLogRepo).save(any());
    }

    @Test
    void upgradeCancelledSubscriptionThrows() {
        UUID subId = UUID.randomUUID();
        Subscription sub = new Subscription(TENANT, SubscriptionPlan.PRO,
                SubscriptionState.CANCELLED, null, null);
        when(subscriptionRepo.findById(subId)).thenReturn(Optional.of(sub));

        assertThatThrownBy(() -> service.upgradePlan(subId, SubscriptionPlan.PRO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cancelled");
    }

    @Test
    void lockContentionThrows() {
        UUID subId = UUID.randomUUID();
        when(distributedLock.tryLock(anyString(), any(Duration.class)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handlePaymentFailure(subId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("locked");
    }

    @Test
    void unknownSubscriptionThrows() {
        UUID subId = UUID.randomUUID();
        when(subscriptionRepo.findById(subId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handlePaymentFailure(subId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown subscription");
    }
}
