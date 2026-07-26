package com.atmospath.platform.saas.repository;

import java.util.List;
import java.util.UUID;

import com.atmospath.platform.saas.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findTop50ByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    /**
     * GDPR erasure (docs/data-retention.md section 3.1, mode a): keep the
     * operational trail for legitimate interest but remove the member link
     * and scrub identifying fields from the JSONB row images.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE audit_log
            SET member_id = NULL,
                changes = changes - 'email' - 'display_name' - 'latitude' - 'longitude'
            WHERE member_id = CAST(:memberId AS uuid)
            """)
    int anonymizeByMemberId(@Param("memberId") UUID memberId);
}
