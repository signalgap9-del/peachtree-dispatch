package com.atmospath.platform.relational;

import java.util.List;
import java.util.UUID;

public interface SavedPlaceRepository {
    void save(SavedPlace place);

    List<SavedPlace> findNearby(UUID userId, double longitude, double latitude, double radiusMiles);
}
