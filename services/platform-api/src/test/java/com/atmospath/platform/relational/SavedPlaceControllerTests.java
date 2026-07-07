package com.atmospath.platform.relational;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.atmospath.platform.account.EntitlementService;
import com.atmospath.platform.account.PlanCode;
import com.atmospath.platform.account.SubscriptionStatus;
import com.atmospath.platform.account.TenantAuthorizationService;
import com.atmospath.platform.account.TenantContext;
import com.atmospath.platform.account.TenantContextResolver;
import com.atmospath.platform.account.TenantRole;
import com.atmospath.platform.idempotency.IdempotencyService;
import com.atmospath.platform.idempotency.InMemoryIdempotencyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class SavedPlaceControllerTests {
    private final FakeSavedPlaceRepository repository = new FakeSavedPlaceRepository();
    private final TenantContextResolver resolver = mock(TenantContextResolver.class);
    private final EntitlementService entitlements = mock(EntitlementService.class);
    private final SavedPlaceController controller = new SavedPlaceController(
            repository,
            resolver,
            entitlements,
            new TenantAuthorizationService(),
            new IdempotencyService(new InMemoryIdempotencyRepository()));

    @Test
    void viewerRoleCannotCreateSavedPlace() {
        var request = new MockHttpServletRequest();
        when(resolver.resolve(null, request)).thenReturn(context(UUID.randomUUID(), TenantRole.VIEWER));

        assertThatThrownBy(() -> controller.create(null, request, "place-save-1", createPlaceRequest()))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void createPlaceIsIdempotentForRepeatedClientRetry() {
        var userId = UUID.randomUUID();
        var request = new MockHttpServletRequest();
        when(resolver.resolve(null, request)).thenReturn(context(userId, TenantRole.OWNER));

        var first = controller.create(null, request, "place-save-1", createPlaceRequest());
        var second = controller.create(null, request, "place-save-1", createPlaceRequest());

        assertThat(second.savedItemId()).isEqualTo(first.savedItemId());
        assertThat(repository.findAll(userId)).hasSize(1);
        verify(entitlements).requireSavedPlaceCapacity(context(userId, TenantRole.OWNER), 0);
    }

    private static SavedPlaceController.CreateSavedPlace createPlaceRequest() {
        return new SavedPlaceController.CreateSavedPlace("Miami Beach", -80.13, 25.7907, 37);
    }

    private static TenantContext context(UUID userId, TenantRole role) {
        return new TenantContext(
                UUID.nameUUIDFromBytes(("tenant:" + userId).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                userId,
                "subject-" + userId,
                "person@example.com",
                PlanCode.FREE,
                SubscriptionStatus.ACTIVE,
                role,
                true);
    }

    private static final class FakeSavedPlaceRepository implements SavedPlaceRepository {
        private final Map<UUID, Map<UUID, SavedPlace>> places = new HashMap<>();

        @Override
        public UUID ensureUser(String authSubject, String email) {
            return UUID.nameUUIDFromBytes(authSubject.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public void save(SavedPlace place) {
            places.computeIfAbsent(place.userId(), ignored -> new HashMap<>()).put(place.savedItemId(), place);
        }

        @Override
        public void saveRoute(SavedRoute route) {
        }

        @Override
        public List<SavedPlace> findAll(UUID userId) {
            return List.copyOf(places.getOrDefault(userId, Map.of()).values());
        }

        @Override
        public Optional<SavedPlace> findPlace(UUID userId, UUID savedItemId) {
            return Optional.ofNullable(places.getOrDefault(userId, Map.of()).get(savedItemId));
        }

        @Override
        public List<SavedRoute> findRoutes(UUID userId) {
            return List.of();
        }

        @Override
        public void recordRouteRiskObservation(RouteRiskObservation observation) {
        }

        @Override
        public List<RouteRiskObservation> findRouteRiskHistory(UUID userId, UUID savedItemId, int limit) {
            return List.of();
        }

        @Override
        public List<SavedPlace> findNearby(UUID userId, double longitude, double latitude, double radiusMiles) {
            return findAll(userId);
        }

        @Override
        public void delete(UUID userId, UUID savedItemId) {
            places.getOrDefault(userId, Map.of()).remove(savedItemId);
        }

        @Override
        public void deleteRoute(UUID userId, UUID savedItemId) {
        }
    }
}
