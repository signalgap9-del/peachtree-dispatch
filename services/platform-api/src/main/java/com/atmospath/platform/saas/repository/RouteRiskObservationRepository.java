package com.atmospath.platform.saas.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.atmospath.platform.saas.entity.RouteRiskObservationEntity;
import com.atmospath.platform.saas.entity.RouteRiskObservationId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteRiskObservationRepository
        extends JpaRepository<RouteRiskObservationEntity, RouteRiskObservationId> {

    List<RouteRiskObservationEntity> findTop10BySavedRouteIdOrderByTimeDesc(UUID savedRouteId);

    /** GDPR erasure: observations have no FK cascade, so delete them explicitly. */
    void deleteBySavedRouteIdIn(Collection<UUID> savedRouteIds);
}
