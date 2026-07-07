package com.atmospath.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.atmospath.platform.account.PlanCode;
import com.atmospath.platform.account.SubscriptionStatus;
import com.atmospath.platform.account.TenantContext;
import com.atmospath.platform.account.TenantRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class IdempotencyServiceTests {
    private final CapturingRepository repository = new CapturingRepository();
    private final IdempotencyService service = new IdempotencyService(repository);
    private final TenantContext context = new TenantContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "subject",
            "person@example.com",
            PlanCode.FREE,
            SubscriptionStatus.ACTIVE,
            TenantRole.OWNER,
            true);

    @Test
    void hashesKeysBeforeRepositoryLookup() {
        var resourceId = UUID.randomUUID();

        service.recordResource(context, "saved-route:create", "mobile-retry-1", resourceId);

        assertThat(repository.lastKeyHash).hasSize(64);
        assertThat(repository.lastKeyHash).doesNotContain("mobile-retry-1");
        assertThat(service.findExistingResource(context, "saved-route:create", "mobile-retry-1")).contains(resourceId);
    }

    @Test
    void blankKeyDisablesIdempotency() {
        service.recordResource(context, "saved-route:create", "   ", UUID.randomUUID());

        assertThat(repository.resources).isEmpty();
        assertThat(service.findExistingResource(context, "saved-route:create", null)).isEmpty();
    }

    @Test
    void rejectsUnsafeKeys() {
        assertThatThrownBy(() -> service.findExistingResource(context, "saved-route:create", "bad key with spaces"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private static final class CapturingRepository implements IdempotencyRepository {
        private final Map<String, UUID> resources = new HashMap<>();
        private String lastKeyHash = "";

        @Override
        public Optional<UUID> findResourceId(UUID tenantId, String operation, String keyHash) {
            lastKeyHash = keyHash;
            return Optional.ofNullable(resources.get(tenantId + "|" + operation + "|" + keyHash));
        }

        @Override
        public void saveResourceId(UUID tenantId, String operation, String keyHash, UUID resourceId) {
            lastKeyHash = keyHash;
            resources.putIfAbsent(tenantId + "|" + operation + "|" + keyHash, resourceId);
        }
    }
}
