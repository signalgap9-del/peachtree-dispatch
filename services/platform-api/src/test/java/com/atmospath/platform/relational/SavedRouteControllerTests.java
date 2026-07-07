package com.atmospath.platform.relational;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
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

class SavedRouteControllerTests {
    private final FakeSavedPlaceRepository repository = new FakeSavedPlaceRepository();
    private final TenantContextResolver resolver = mock(TenantContextResolver.class);
    private final EntitlementService entitlements = mock(EntitlementService.class);
    private final SavedRouteController controller = new SavedRouteController(
            repository,
            resolver,
            entitlements,
            new TenantAuthorizationService(),
            new IdempotencyService(new InMemoryIdempotencyRepository()));

    @Test
    void ownedRouteLookupDoesNotLeakRoutesFromAnotherUser() {
        var owner = UUID.randomUUID();
        var attacker = UUID.randomUUID();
        var route = route(owner);
        repository.saveRoute(route);
        var request = new MockHttpServletRequest();
        when(resolver.resolve(null, request)).thenReturn(context(attacker, TenantRole.OWNER));

        assertThatThrownBy(() -> controller.get(null, request, route.savedItemId()))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void viewerRoleCannotListSavedRoutes() {
        var request = new MockHttpServletRequest();
        when(resolver.resolve(null, request)).thenReturn(context(UUID.randomUUID(), TenantRole.VIEWER));

        assertThatThrownBy(() -> controller.list(null, request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void createRouteIsIdempotentForRepeatedClientRetry() {
        var userId = UUID.randomUUID();
        var request = new MockHttpServletRequest();
        when(resolver.resolve(null, request)).thenReturn(context(userId, TenantRole.OWNER));
        var command = createRouteRequest();

        var first = controller.create(null, request, "route-save-1", command);
        var second = controller.create(null, request, "route-save-1", command);

        assertThat(second.savedItemId()).isEqualTo(first.savedItemId());
        assertThat(repository.findRoutes(userId)).hasSize(1);
        assertThat(repository.observations).hasSize(1);
        verify(entitlements).requireSavedRouteCapacity(context(userId, TenantRole.OWNER), 0);
    }

    @Test
    void refreshRiskWritesDurableObservationAndUpdatesLastCheckedAt() {
        var userId = UUID.randomUUID();
        var route = route(userId);
        repository.saveRoute(route);
        var request = new MockHttpServletRequest();
        when(resolver.resolve(null, request)).thenReturn(context(userId, TenantRole.OWNER));

        var risk = controller.refreshRisk(null, request, route.savedItemId(), "refresh-1");

        assertThat(risk.savedItemId()).isEqualTo(route.savedItemId());
        assertThat(risk.lastCheckedAt()).isNotEqualTo(route.lastCheckedAt());
        assertThat(repository.observations).extracting(RouteRiskObservation::source)
                .containsExactly("SAVED_ROUTE_MONITOR_REFRESH");
    }

    private static SavedRouteController.CreateSavedRoute createRouteRequest() {
        return new SavedRouteController.CreateSavedRoute(
                "Atlanta to Savannah",
                "Atlanta, GA",
                "Savannah, GA",
                "CAR",
                248,
                240,
                18,
                42,
                List.of(List.of(-84.388, 33.749), List.of(-81.0998, 32.0809)),
                "2026-07-07T08:00:00Z",
                "08:00",
                55,
                true,
                List.of("Thunderstorm"));
    }

    private static SavedRoute route(UUID userId) {
        return new SavedRoute(
                UUID.randomUUID(),
                userId,
                "Atlanta to Savannah",
                "Atlanta, GA",
                "Savannah, GA",
                "CAR",
                248,
                240,
                18,
                42,
                List.of(List.of(-84.388, 33.749), List.of(-81.0998, 32.0809)),
                "2026-07-07T08:00:00Z");
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
        private final Map<UUID, Map<UUID, SavedRoute>> routes = new HashMap<>();
        private final List<RouteRiskObservation> observations = new ArrayList<>();

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
            routes.computeIfAbsent(route.userId(), ignored -> new HashMap<>()).put(route.savedItemId(), route);
        }

        @Override
        public List<SavedPlace> findAll(UUID userId) {
            return List.copyOf(places.getOrDefault(userId, Map.of()).values());
        }

        @Override
        public List<SavedRoute> findRoutes(UUID userId) {
            return List.copyOf(routes.getOrDefault(userId, Map.of()).values());
        }

        @Override
        public Optional<SavedRoute> findRoute(UUID userId, UUID savedItemId) {
            return Optional.ofNullable(routes.getOrDefault(userId, Map.of()).get(savedItemId));
        }

        @Override
        public void recordRouteRiskObservation(RouteRiskObservation observation) {
            observations.add(observation);
        }

        @Override
        public List<RouteRiskObservation> findRouteRiskHistory(UUID userId, UUID savedItemId, int limit) {
            return observations.stream()
                    .filter(observation -> observation.userId().equals(userId) && observation.savedItemId().equals(savedItemId))
                    .limit(limit)
                    .toList();
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
            routes.getOrDefault(userId, Map.of()).remove(savedItemId);
        }
    }
}
