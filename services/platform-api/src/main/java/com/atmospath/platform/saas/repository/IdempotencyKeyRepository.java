package com.atmospath.platform.saas.repository;

import java.util.Optional;
import java.util.UUID;

import com.atmospath.platform.saas.entity.IdempotencyKeyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyRecord, java.io.Serializable> {

    Optional<IdempotencyKeyRecord> findByTenantIdAndOperationAndKeyHash(UUID tenantId, String operation, String keyHash);
}
