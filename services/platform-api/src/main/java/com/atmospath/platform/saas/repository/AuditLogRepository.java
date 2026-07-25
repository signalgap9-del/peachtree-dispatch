package com.atmospath.platform.saas.repository;

import java.util.List;
import java.util.UUID;

import com.atmospath.platform.saas.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findTop50ByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
