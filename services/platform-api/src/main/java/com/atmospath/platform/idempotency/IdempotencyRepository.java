package com.atmospath.platform.idempotency;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRepository {
    Optional<UUID> findResourceId(UUID tenantId, String operation, String keyHash);

    void saveResourceId(UUID tenantId, String operation, String keyHash, UUID resourceId);
}
