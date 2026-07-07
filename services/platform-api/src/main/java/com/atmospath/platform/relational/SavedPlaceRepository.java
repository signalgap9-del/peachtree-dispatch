package com.atmospath.platform.relational;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SavedPlaceRepository {
    UUID ensureUser(String authSubject, String email);

    void save(SavedPlace place);

    void saveRoute(SavedRoute route);

    List<SavedPlace> findAll(UUID userId);

    List<SavedRoute> findRoutes(UUID userId);

    default Optional<SavedPlace> findPlace(UUID userId, UUID savedItemId) {
        return findAll(userId).stream()
                .filter(place -> place.savedItemId().equals(savedItemId))
                .findFirst();
    }

    default Optional<SavedRoute> findRoute(UUID userId, UUID savedItemId) {
        return findRoutes(userId).stream()
                .filter(route -> route.savedItemId().equals(savedItemId))
                .findFirst();
    }

    void recordRouteRiskObservation(RouteRiskObservation observation);

    List<RouteRiskObservation> findRouteRiskHistory(UUID userId, UUID savedItemId, int limit);

    List<SavedPlace> findNearby(UUID userId, double longitude, double latitude, double radiusMiles);

    void delete(UUID userId, UUID savedItemId);

    void deleteRoute(UUID userId, UUID savedItemId);
}
