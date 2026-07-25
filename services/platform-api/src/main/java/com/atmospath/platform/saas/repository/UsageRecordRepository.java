package com.atmospath.platform.saas.repository;

import java.time.LocalDate;
import java.util.UUID;

import com.atmospath.platform.saas.entity.UsageRecord;
import com.atmospath.platform.saas.entity.UsageRecordId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, UsageRecordId> {

    /**
     * Atomic server-side quota check: increments today's usage row and
     * reports whether the tenant is still inside the entitlement limit.
     */
    @Query(nativeQuery = true, value = "SELECT check_and_consume_quota(CAST(:tenantId AS uuid), :feature)")
    boolean checkAndConsumeQuota(@Param("tenantId") UUID tenantId, @Param("feature") String feature);

    /**
     * Reconcile a Redis counter into the partitioned usage table. The schema
     * has no unique constraint on (tenant_id, feature, usage_date), so
     * reconciliation replaces the day's rows with the authoritative counter.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            DELETE FROM usage_record
            WHERE tenant_id = CAST(:tenantId AS uuid)
              AND feature = :feature
              AND usage_date = CAST(:usageDate AS date)
            """)
    void deleteForDate(@Param("tenantId") UUID tenantId,
                       @Param("feature") String feature,
                       @Param("usageDate") LocalDate usageDate);

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO usage_record (tenant_id, feature, usage_date, count)
            VALUES (CAST(:tenantId AS uuid), :feature, CAST(:usageDate AS date), :count)
            """)
    void insertCount(@Param("tenantId") UUID tenantId,
                     @Param("feature") String feature,
                     @Param("usageDate") LocalDate usageDate,
                     @Param("count") int count);

    @Query(nativeQuery = true, value = """
            SELECT COALESCE(SUM(count), 0) FROM usage_record
            WHERE tenant_id = CAST(:tenantId AS uuid)
              AND feature = :feature
              AND usage_date = CAST(:usageDate AS date)
            """)
    long sumForDate(@Param("tenantId") UUID tenantId,
                    @Param("feature") String feature,
                    @Param("usageDate") LocalDate usageDate);
}
