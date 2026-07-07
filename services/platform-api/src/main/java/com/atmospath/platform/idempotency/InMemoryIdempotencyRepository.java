package com.atmospath.platform.idempotency;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "atmospath.idempotency-store", havingValue = "memory", matchIfMissing = true)
public class InMemoryIdempotencyRepository implements IdempotencyRepository {
    private final ConcurrentHashMap<IdempotencyLookupKey, UUID> resources = new ConcurrentHashMap<>();

    @Override
    public Optional<UUID> findResourceId(UUID tenantId, String operation, String keyHash) {
        return Optional.ofNullable(resources.get(new IdempotencyLookupKey(tenantId, operation, keyHash)));
    }

    @Override
    public void saveResourceId(UUID tenantId, String operation, String keyHash, UUID resourceId) {
        resources.putIfAbsent(new IdempotencyLookupKey(tenantId, operation, keyHash), resourceId);
    }

    private record IdempotencyLookupKey(UUID tenantId, String operation, String keyHash) {
        private IdempotencyLookupKey {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(keyHash, "keyHash");
        }
    }
}
