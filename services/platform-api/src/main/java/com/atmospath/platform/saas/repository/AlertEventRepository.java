package com.atmospath.platform.saas.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.atmospath.platform.saas.entity.AlertEvent;
import com.atmospath.platform.saas.entity.AlertState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertEventRepository extends JpaRepository<AlertEvent, UUID> {

    /** Alerts that still need attention: anything not acknowledged or resolved. */
    @Query("""
            select e from AlertEvent e
            where e.savedRouteId = :savedRouteId
              and e.state not in (com.atmospath.platform.saas.entity.AlertState.ACKNOWLEDGED,
                                  com.atmospath.platform.saas.entity.AlertState.RESOLVED)
            order by e.triggeredAt desc
            """)
    List<AlertEvent> findOpenByRouteId(@Param("savedRouteId") UUID savedRouteId);

    long countByTenantIdAndStateAndTriggeredAtAfter(UUID tenantId, AlertState state, Instant triggeredAt);

    /**
     * Validates and applies a state transition through the database function.
     * The function returns void and raises an exception when the transition
     * is illegal or the event does not exist, so callers translate database
     * exceptions into {@code IllegalStateException}.
     */
    @Query(nativeQuery = true, value = "SELECT transition_alert_state(CAST(:eventId AS uuid), :toState) IS NULL")
    boolean transitionState(@Param("eventId") UUID eventId, @Param("toState") String toState);
}
