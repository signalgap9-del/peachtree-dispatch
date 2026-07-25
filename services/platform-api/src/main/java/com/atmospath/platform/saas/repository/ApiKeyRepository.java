package com.atmospath.platform.saas.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.atmospath.platform.saas.entity.ApiKeyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKeyRecord, UUID> {

    Optional<ApiKeyRecord> findByKeyHash(String keyHash);

    List<ApiKeyRecord> findByTenantId(UUID tenantId);
}
