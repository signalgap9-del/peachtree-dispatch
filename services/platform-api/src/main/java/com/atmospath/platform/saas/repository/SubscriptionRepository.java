package com.atmospath.platform.saas.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.atmospath.platform.saas.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    /**
     * The subscription that currently grants entitlements for a tenant:
     * its validity window covers {@code now}. When several overlap (renewal
     * races), the one expiring latest wins.
     */
    @Query("""
            select s from Subscription s
            where s.tenantId = :tenantId
              and s.validFrom <= :now
              and s.validTo > :now
            order by s.validTo desc
            """)
    List<Subscription> findActiveByTenantId(@Param("tenantId") UUID tenantId, @Param("now") Instant now);

    default Optional<Subscription> findActiveByTenantId(UUID tenantId) {
        return findActiveByTenantId(tenantId, Instant.now()).stream().findFirst();
    }

    List<Subscription> findByTenantIdOrderByValidToDesc(UUID tenantId);

    /**
     * Server-side proration: computes the credit for the unused part of the
     * current period, closes this subscription row, and inserts a new
     * version with the new plan. Returns the function's JSONB result as
     * text: {@code {new_subscription_id, previous_plan, new_plan,
     * credit_ratio, days_remaining, days_total}}.
     */
    @Query(nativeQuery = true,
            value = "SELECT prorate_subscription(CAST(:subscriptionId AS uuid), :newPlan)::text")
    String prorateSubscription(@Param("subscriptionId") UUID subscriptionId,
                               @Param("newPlan") String newPlan);
}
