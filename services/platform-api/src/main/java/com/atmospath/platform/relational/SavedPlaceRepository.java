package com.atmospath.platform.relational;

import java.util.List;
import java.util.UUID;

public interface SavedPlaceRepository {
    UUID ensureUser(String authSubject, String email);

    void save(SavedPlace place);

    List<SavedPlace> findAll(UUID userId);

    List<SavedPlace> findNearby(UUID userId, double longitude, double latitude, double radiusMiles);

    void delete(UUID userId, UUID savedItemId);
}
