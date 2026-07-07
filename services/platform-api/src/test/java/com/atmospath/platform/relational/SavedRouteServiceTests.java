package com.atmospath.platform.relational;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.atmospath.platform.account.EntitlementService;
import com.atmospath.platform.account.MeteredFeature;
import com.atmospath.platform.account.PlanCode;
import com.atmospath.platform.account.QuotaExceededException;
import com.atmospath.platform.account.SubscriptionStatus;
import com.atmospath.platform.account.TenantContext;
import com.atmospath.platform.account.TenantRole;
import com.atmospath.platform.idempotency.IdempotencyService;
import com.atmospath.platform.idempotency.InMemoryIdempotencyRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class SavedRouteServiceTests {
    private static final Instant NOW = Instant.parse("2026-07-07T09:00:00Z");

    private final FakeSavedPlaceRepository repository = new FakeSavedPlaceRepository();
    private final EntitlementService entitlements = mock(EntitlementService.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final SavedRouteService service = new SavedRouteService(
            repository,
            entitlements,
            new IdempotencyService(new InMemoryIdempotencyRepository()),
            Clock.fixed(NOW, ZoneOffset.UTC),
            meterRegistry);

    @Test
    void createWithSameIdempotencyKeyReturnsOriginalRouteWithoutDuplicateWrites() {
        var context = context(UUID.randomUUID(), TenantRole.OWNER);
        var command = createCommand();

        var first = service.create(context, command, "route-save-1");
        var second = service.create(context, command, "route-save-1");

        assertThat(second.savedItemId()).isEqualTo(first.savedItemId());
        assertThat(repository.findRoutes(context.userId())).hasSize(1);
        assertThat(repository.routeSaveCount).isEqualTo(1);
        assertThat(repository.observations).extracting(RouteRiskObservation::source)
                .containsExactly("SAVED_ROUTE_CREATED");
        assertThat(meterCount("create", "created")).isEqualTo(1);
        assertThat(meterCount("create", "idempotency_hit")).isEqualTo(1);
        verify(entitlements).requireSavedRouteCapacity(context, 0);
    }

    @Test
    void createChecksPlanCapacityBeforePersistingRouteOrObservation() {
        var context = context(UUID.randomUUID(), TenantRole.OWNER);
        doThrow(new QuotaExceededException(
                MeteredFeature.SAVED_ROUTE,
                PlanCode.FREE,
                3,
                3,
                "2026-07-08T00:00:00Z"))
                .when(entitlements)
                .requireSavedRouteCapacity(context, 0);

        assertThatThrownBy(() -> service.create(context, createCommand(), "route-save-2"))
                .isInstanceOf(QuotaExceededException.class);

        assertThat(repository.findRoutes(context.userId())).isEmpty();
        assertThat(repository.observations).isEmpty();
        assertThat(repository.routeSaveCount).isZero();
    }

    @Test
    void refreshRiskWithSameIdempotencyKeyRecordsOnlyOneObservation() {
        var context = context(UUID.randomUUID(), TenantRole.OWNER);
        var route = route(context.userId());
        repository.saveRoute(route);
        repository.routeSaveCount = 0;

        var first = service.refreshRisk(context, route.savedItemId(), "refresh-1");
        var second = service.refreshRisk(context, route.savedItemId(), "refresh-1");

        assertThat(first).isEqualTo(second);
        assertThat(first.lastCheckedAt()).isEqualTo(NOW.toString());
        assertThat(repository.routeSaveCount).isEqualTo(1);
        assertThat(repository.observations).extracting(RouteRiskObservation::source)
                .containsExactly("SAVED_ROUTE_MONITOR_REFRESH");
        assertThat(meterCount("refresh", "refreshed")).isEqualTo(1);
        assertThat(meterCount("refresh", "idempotency_hit")).isEqualTo(1);
    }

    @Test
    void riskHistoryFallsBackToLegacyRouteStateWhenNoObservationsExist() {
        var context = context(UUID.randomUUID(), TenantRole.OWNER);
        var route = route(context.userId());
        repository.saveRoute(route);

        var history = service.riskHistory(context, route.savedItemId());

        assertThat(history).singleElement().satisfies(point -> {
            assertThat(point.checkedAt()).isEqualTo(route.lastCheckedAt());
            assertThat(point.riskScore()).isEqualTo(route.riskScore());
            assertThat(point.source()).isEqualTo("LEGACY_ROUTE_STATE");
            assertThat(point.modelVersion()).isEqualTo("route-risk-v1");
        });
    }

    private double meterCount(String operation, String outcome) {
        var counter = meterRegistry.find("atmospath.saved_route.commands")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private static SavedRouteService.CreateSavedRouteCommand createCommand() {
        return new SavedRouteService.CreateSavedRouteCommand(
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
                UUID.nameUUIDFromBytes(("tenant:" + userId).getBytes(StandardCharsets.UTF_8)),
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
        private int routeSaveCount;

        @Override
        public UUID ensureUser(String authSubject, String email) {
            return UUID.nameUUIDFromBytes(authSubject.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void save(SavedPlace place) {
            places.computeIfAbsent(place.userId(), ignored -> new HashMap<>()).put(place.savedItemId(), place);
        }

        @Override
        public void saveRoute(SavedRoute route) {
            routeSaveCount += 1;
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
