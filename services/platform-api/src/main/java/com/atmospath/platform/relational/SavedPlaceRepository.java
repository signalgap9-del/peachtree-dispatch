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

    default Optional<SavedRoute> findRoute(UUID userId, UUID savedItemId) {
        return findRoutes(userId).stream()
                .filter(route -> route.savedItemId().equals(savedItemId))
                .findFirst();
    }

    List<SavedPlace> findNearby(UUID userId, double longitude, double latitude, double radiusMiles);

    void delete(UUID userId, UUID savedItemId);

    void deleteRoute(UUID userId, UUID savedItemId);
}
