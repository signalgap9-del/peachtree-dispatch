package com.atmospath.platform.saas.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Supplier;

import com.atmospath.platform.saas.entity.AuditLog;
import com.atmospath.platform.saas.entity.Subscription;
import com.atmospath.platform.saas.entity.SubscriptionPlan;
import com.atmospath.platform.saas.entity.SubscriptionState;
import com.atmospath.platform.saas.redis.RedisDistributedLock;
import com.atmospath.platform.saas.redis.RedisDistributedLock.LockHandle;
import com.atmospath.platform.saas.repository.AuditLogRepository;
import com.atmospath.platform.saas.repository.SubscriptionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Subscription state machine: trial start, plan upgrades with proration,
 * payment-failure dunning (ACTIVE -> PAST_DUE -> GRACE -> CANCELLED), and
 * renewal. Every mutation runs under a Redis distributed lock so only one
 * node touches a subscription at a time; the {@code version} column guards
 * against lost updates.
 *
 * <p>The schema is temporal: open rows carry {@code valid_to = 'infinity'}
 * and proration closes the current row and inserts a new version through
 * {@code prorate_subscription()}.
 */
@Service
@ConditionalOnProperty(name = "atmospath.saas.enabled", havingValue = "true")
public class SubscriptionLifecycleService {

    static final int TRIAL_DAYS = 14;
    static final int RENEWAL_DAYS = 30;
    static final SubscriptionPlan TRIAL_PLAN = SubscriptionPlan.PRO;

    private static final Logger log = LoggerFactory.getLogger(SubscriptionLifecycleService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final AuditLogRepository auditLogRepository;
    private final RedisDistributedLock distributedLock;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public SubscriptionLifecycleService(SubscriptionRepository subscriptionRepository,
                                        AuditLogRepository auditLogRepository,
                                        RedisDistributedLock distributedLock,
                                        @Qualifier("saasTransactionManager")
                                        PlatformTransactionManager transactionManager,
                                        ObjectMapper objectMapper) {
        this.subscriptionRepository = subscriptionRepository;
        this.auditLogRepository = auditLogRepository;
        this.distributedLock = distributedLock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    public Subscription startTrial(UUID tenantId) {
        return transactionTemplate.execute(status -> {
            subscriptionRepository.findActiveByTenantId(tenantId).ifPresent(existing -> {
                throw new IllegalStateException(
                        "Tenant " + tenantId + " already has an active subscription: " + existing.getId());
            });
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            Subscription subscription = new Subscription(
                    tenantId, TRIAL_PLAN, SubscriptionState.TRIAL, today, today.plusDays(TRIAL_DAYS));
            subscriptionRepository.save(subscription);
            audit(subscription, "TRIAL_STARTED", "{\"plan\":\"" + TRIAL_PLAN + "\"}");
            log.info("Started {}-day {} trial for tenant {}: {}",
                    TRIAL_DAYS, TRIAL_PLAN, tenantId, subscription.getId());
            return subscription;
        });
    }

    /**
     * Upgrades the plan under the subscription lock. The database function
     * prorates the remaining period, closes the current row, and inserts a
     * new subscription version ({@code version + 1}, status ACTIVE).
     *
     * @return the new subscription version
     */
    public Subscription upgradePlan(UUID subscriptionId, SubscriptionPlan newPlan) {
        return withLock(subscriptionId, () -> transactionTemplate.execute(status -> {
            Subscription current = load(subscriptionId);
            if (current.getStatus() == SubscriptionState.CANCELLED) {
                throw new IllegalStateException("Cannot upgrade a cancelled subscription: " + subscriptionId);
            }
            String resultJson = subscriptionRepository.prorateSubscription(subscriptionId, newPlan.name());
            UUID newSubscriptionId = parseNewSubscriptionId(resultJson);
            Subscription upgraded = load(newSubscriptionId);
            audit(upgraded, "PLAN_UPGRADED", resultJson);
            log.info("Subscription {} upgraded {} -> {} as new version {}",
                    subscriptionId, current.getPlan(), newPlan, newSubscriptionId);
            return upgraded;
        }));
    }

    /**
     * Dunning ladder. Each reported failure advances the subscription one
     * step: ACTIVE/TRIAL -> PAST_DUE -> GRACE -> CANCELLED. Open rows keep
     * {@code valid_to = 'infinity'} until cancellation; the grace window is
     * enforced by the payment retry schedule.
     */
    public Subscription handlePaymentFailure(UUID subscriptionId) {
        return withLock(subscriptionId, () -> transactionTemplate.execute(status -> {
            Subscription subscription = load(subscriptionId);
            switch (subscription.getStatus()) {
                case TRIAL, ACTIVE -> {
                    subscription.setStatus(SubscriptionState.PAST_DUE);
                    audit(subscription, "PAYMENT_FAILED", "{\"marked\":\"PAST_DUE\"}");
                }
                case PAST_DUE -> {
                    subscription.setStatus(SubscriptionState.GRACE);
                    audit(subscription, "PAYMENT_FAILED", "{\"marked\":\"GRACE\"}");
                }
                case GRACE -> {
                    subscription.setStatus(SubscriptionState.CANCELLED);
                    subscription.setValidTo(Instant.now());
                    audit(subscription, "SUBSCRIPTION_CANCELLED", "{\"reason\":\"grace period exhausted\"}");
                }
                case CANCELLED -> log.info("Payment failure reported for already-cancelled subscription {}",
                        subscriptionId);
            }
            subscriptionRepository.saveAndFlush(subscription);
            log.info("Subscription {} payment failure handled; status={}",
                    subscriptionId, subscription.getStatus());
            return subscription;
        }));
    }

    /** Extends the billing period by {@value RENEWAL_DAYS} days and restores ACTIVE. */
    public Subscription renewSubscription(UUID subscriptionId) {
        return withLock(subscriptionId, () -> transactionTemplate.execute(status -> {
            Subscription subscription = load(subscriptionId);
            if (subscription.getStatus() == SubscriptionState.CANCELLED) {
                throw new IllegalStateException("Cannot renew a cancelled subscription: " + subscriptionId);
            }
            LocalDate previousEnd = subscription.getCurrentPeriodEnd() == null
                    ? LocalDate.now(ZoneOffset.UTC)
                    : subscription.getCurrentPeriodEnd();
            LocalDate newEnd = previousEnd.plusDays(RENEWAL_DAYS);
            subscription.setCurrentPeriodEnd(newEnd);
            subscription.setValidTo(Subscription.VALID_FOREVER);
            subscription.setStatus(SubscriptionState.ACTIVE);
            subscriptionRepository.saveAndFlush(subscription);
            audit(subscription, "SUBSCRIPTION_RENEWED", "{\"period_end\":\"" + newEnd + "\"}");
            log.info("Subscription {} renewed until {}", subscriptionId, newEnd);
            return subscription;
        }));
    }

    private Subscription load(UUID subscriptionId) {
        return subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown subscription: " + subscriptionId));
    }

    private UUID parseNewSubscriptionId(String resultJson) {
        try {
            JsonNode result = objectMapper.readTree(resultJson);
            return UUID.fromString(result.path("new_subscription_id").asText());
        } catch (Exception ex) {
            throw new IllegalStateException("Unparseable proration result: " + resultJson, ex);
        }
    }

    private <T> T withLock(UUID subscriptionId, Supplier<T> action) {
        LockHandle lock = distributedLock
                .tryLock("subscription:" + subscriptionId, RedisDistributedLock.SUBSCRIPTION_LOCK_TTL)
                .orElseThrow(() -> new IllegalStateException(
                        "Subscription " + subscriptionId + " is locked by another operation; retry shortly"));
        try {
            return action.get();
        } finally {
            if (!distributedLock.unlock(lock)) {
                log.warn("Subscription lock {} expired before release", lock.key());
            }
        }
    }

    private void audit(Subscription subscription, String action, String changes) {
        auditLogRepository.save(new AuditLog(
                subscription.getTenantId(), null, action, "subscription", subscription.getId(), changes));
    }
}
