package com.atmospath.platform.relational;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class SavedRouteControllerTests {
    private final InMemorySavedPlaceRepository repository = new InMemorySavedPlaceRepository();
    private final SavedRouteController controller = new SavedRouteController(repository);
    private final Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("sub", "user-subject")
            .claim("email", "person@example.com")
            .build();

    @Test
    void recordsRouteObservationsAgainstOwnedSavedRoute() {
        var route = savedRoute();
        repository.saveRoute(route);

        var observation = controller.createObservation(jwt, route.savedItemId(), new SavedRouteController.CreateRouteObservation(
                "2026-07-04T13:00:00Z",
                145.0,
                132.0,
                63,
                List.of("Flash Flood Warning", "Lane closure"),
                "Heavy rain from NWS alerts near the route.",
                "WZDx lane closure near I-75.",
                "USER_REPORTED",
                "Traffic slowed for about 20 minutes."));

        assertThat(observation.savedItemId()).isEqualTo(route.savedItemId());
        assertThat(observation.userId()).isEqualTo(route.userId());
        assertThat(observation.delayMinutes()).isEqualTo(13.0);
        assertThat(observation.featureSchemaVersion()).isEqualTo("saved-route-observation-v1");
        assertThat(repository.findRouteObservations(route.userId(), route.savedItemId())).containsExactly(observation);
    }

    @Test
    void exportsTrainingExamplesWithRouteFeaturesAndObservationLabels() {
        var route = savedRoute();
        repository.saveRoute(route);
        controller.createObservation(jwt, route.savedItemId(), new SavedRouteController.CreateRouteObservation(
                "2026-07-04T13:00:00Z",
                145.0,
                null,
                63,
                List.of("Flash Flood Warning"),
                "Heavy rain from NWS alerts near the route.",
                "No road closure reported.",
                "USER_REPORTED",
                null));

        var examples = controller.mlDataset(jwt, route.savedItemId());

        assertThat(examples).hasSize(1);
        assertThat(examples.getFirst().vehicleType()).isEqualTo("CAR");
        assertThat(examples.getFirst().plannedDurationMinutes()).isEqualTo(route.durationMinutes());
        assertThat(examples.getFirst().actualDurationMinutes()).isEqualTo(145.0);
        assertThat(examples.getFirst().delayLabelMinutes()).isEqualTo(25.0);
        assertThat(examples.getFirst().plannedHazards()).containsExactly("Thunderstorm");
        assertThat(examples.getFirst().encounteredHazards()).containsExactly("Flash Flood Warning");
    }

    private static SavedRoute savedRoute() {
        var userId = UUID.nameUUIDFromBytes("user-subject".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new SavedRoute(
                UUID.randomUUID(),
                userId,
                "Atlanta to Savannah",
                "Atlanta, GA",
                "Savannah, GA",
                "CAR",
                248,
                120,
                18,
                44,
                List.of(List.of(-84.388, 33.749), List.of(-81.091, 32.081)),
                "2026-07-04T12:00:00Z",
                "08:00",
                55,
                true,
                "2026-07-04T12:05:00Z",
                List.of("Thunderstorm"),
                "ELEVATED");
    }

    private static final class InMemorySavedPlaceRepository implements SavedPlaceRepository {
        private final List<SavedRoute> routes = new ArrayList<>();
        private final List<SavedRouteObservation> observations = new ArrayList<>();

        @Override
        public UUID ensureUser(String authSubject, String email) {
            return UUID.nameUUIDFromBytes(authSubject.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public void save(SavedPlace place) {
        }

        @Override
        public void saveRoute(SavedRoute route) {
            routes.removeIf(existing -> existing.savedItemId().equals(route.savedItemId()));
            routes.add(route);
        }

        @Override
        public void saveRouteObservation(SavedRouteObservation observation) {
            observations.add(observation);
        }

        @Override
        public List<SavedPlace> findAll(UUID userId) {
            return List.of();
        }

        @Override
        public List<SavedRoute> findRoutes(UUID userId) {
            return routes.stream().filter(route -> route.userId().equals(userId)).toList();
        }

        @Override
        public List<SavedRouteObservation> findRouteObservations(UUID userId, UUID savedItemId) {
            return observations.stream()
                    .filter(observation -> observation.userId().equals(userId))
                    .filter(observation -> observation.savedItemId().equals(savedItemId))
                    .toList();
        }

        @Override
        public Optional<SavedRoute> findRoute(UUID userId, UUID savedItemId) {
            return findRoutes(userId).stream()
                    .filter(route -> route.savedItemId().equals(savedItemId))
                    .findFirst();
        }

        @Override
        public List<SavedPlace> findNearby(UUID userId, double longitude, double latitude, double radiusMiles) {
            return List.of();
        }

        @Override
        public void delete(UUID userId, UUID savedItemId) {
        }

        @Override
        public void deleteRoute(UUID userId, UUID savedItemId) {
        }
    }
}
