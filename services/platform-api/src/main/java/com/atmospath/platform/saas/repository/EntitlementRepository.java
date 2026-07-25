package com.atmospath.platform.saas.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.atmospath.platform.saas.entity.Entitlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EntitlementRepository extends JpaRepository<Entitlement, UUID> {

    List<Entitlement> findBySubscriptionId(UUID subscriptionId);

    /**
     * Daily quota limit for a feature, resolved through the tenant's
     * currently valid subscription (TRIAL/ACTIVE/GRACE grant entitlements).
     */
    @Query(nativeQuery = true, value = """
            SELECT e.quota_limit
            FROM entitlement e
            JOIN subscription s ON s.id = e.subscription_id
            WHERE s.tenant_id = CAST(:tenantId AS uuid)
              AND e.feature = :feature
              AND s.status IN ('TRIAL', 'ACTIVE', 'GRACE')
              AND s.valid_from <= now()
              AND s.valid_to > now()
            ORDER BY s.valid_to DESC
            LIMIT 1
            """)
    Optional<Integer> findActiveQuotaLimit(@Param("tenantId") UUID tenantId, @Param("feature") String feature);
}
