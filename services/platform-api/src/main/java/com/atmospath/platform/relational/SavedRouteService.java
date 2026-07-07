package com.atmospath.platform.relational;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.atmospath.platform.account.EntitlementService;
import com.atmospath.platform.account.TenantContext;
import com.atmospath.platform.idempotency.IdempotencyService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name = "atmospath.auth.enabled", havingValue = "true")
public class SavedRouteService {
    private static final String CREATE_OPERATION = "saved-route:create";
    private static final String REFRESH_OPERATION_PREFIX = "saved-route:refresh:";
    private static final String COMMAND_METRIC = "atmospath.saved_route.commands";

    private final SavedPlaceRepository repository;
    private final EntitlementService entitlements;
    private final IdempotencyService idempotencyService;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    @Autowired
    public SavedRouteService(
            SavedPlaceRepository repository,
            EntitlementService entitlements,
            IdempotencyService idempotencyService,
            MeterRegistry meterRegistry) {
        this(repository, entitlements, idempotencyService, Clock.systemUTC(), meterRegistry);
    }

    SavedRouteService(
            SavedPlaceRepository repository,
            EntitlementService entitlements,
            IdempotencyService idempotencyService,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.entitlements = entitlements;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    public List<SavedRoute> list(TenantContext context) {
        return repository.findRoutes(context.userId());
    }

    public SavedRoute create(TenantContext context, CreateSavedRouteCommand command, String idempotencyKey) {
        var existingResourceId = idempotencyService.findExistingResource(context, CREATE_OPERATION, idempotencyKey);
        if (existingResourceId.isPresent()) {
            recordCommand("create", "idempotency_hit");
            return repository.findRoute(context.userId(), existingResourceId.get())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Idempotent saved route is not available."));
        }

        entitlements.requireSavedRouteCapacity(context, repository.findRoutes(context.userId()).size());
        var route = new SavedRoute(
                UUID.randomUUID(),
                context.userId(),
                command.name(),
                command.originName(),
                command.destinationName(),
                command.vehicleType(),
                command.distanceMiles(),
                command.durationMinutes(),
                command.climateDelayMinutes(),
                command.riskScore(),
                command.coordinates(),
                command.generatedAt(),
                command.usualDepartureTime() == null ? "08:00" : command.usualDepartureTime(),
                command.riskThreshold() == null ? 55 : command.riskThreshold(),
                command.monitorEnabled() == null || command.monitorEnabled(),
                command.generatedAt(),
                command.activeHazards() == null ? List.of() : List.copyOf(command.activeHazards()),
                "STABLE");
        repository.saveRoute(route);
        repository.recordRouteRiskObservation(RouteRiskObservation.fromRoute(route, "SAVED_ROUTE_CREATED"));
        idempotencyService.recordResource(context, CREATE_OPERATION, idempotencyKey, route.savedItemId());
        recordCommand("create", "created");
        return route;
    }

    public SavedRoute get(TenantContext context, UUID savedItemId) {
        return findOwnedRoute(context, savedItemId);
    }

    public SavedRoute update(TenantContext context, UUID savedItemId, UpdateSavedRouteCommand command) {
        var existing = findOwnedRoute(context, savedItemId);
        var updated = new SavedRoute(
                existing.savedItemId(),
                existing.userId(),
                command.name() == null ? existing.name() : command.name(),
                existing.originName(),
                existing.destinationName(),
                existing.vehicleType(),
                existing.distanceMiles(),
                existing.durationMinutes(),
                existing.climateDelayMinutes(),
                existing.riskScore(),
                existing.coordinates(),
                existing.generatedAt(),
                command.usualDepartureTime() == null ? existing.usualDepartureTime() : command.usualDepartureTime(),
                command.riskThreshold() == null ? existing.riskThreshold() : command.riskThreshold(),
                command.monitorEnabled() == null ? existing.monitorEnabled() : command.monitorEnabled(),
                existing.lastCheckedAt(),
                existing.activeHazards(),
                existing.riskTrend());
        repository.saveRoute(updated);
        repository.recordRouteRiskObservation(RouteRiskObservation.fromRoute(updated, "SAVED_ROUTE_UPDATED"));
        recordCommand("update", "updated");
        return updated;
    }

    public RouteRiskSnapshot currentRisk(TenantContext context, UUID savedItemId) {
        return toRisk(findOwnedRoute(context, savedItemId));
    }

    public RouteRiskSnapshot refreshRisk(TenantContext context, UUID savedItemId, String idempotencyKey) {
        var operation = REFRESH_OPERATION_PREFIX + savedItemId;
        var existingRefresh = idempotencyService.findExistingResource(context, operation, idempotencyKey);
        var route = findOwnedRoute(context, savedItemId);
        if (existingRefresh.isPresent()) {
            if (!existingRefresh.get().equals(savedItemId)) {
                recordCommand("refresh", "idempotency_conflict");
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotent saved route refresh conflicts with another route.");
            }
            recordCommand("refresh", "idempotency_hit");
            return toRisk(route);
        }

        var refreshed = new SavedRoute(
                route.savedItemId(),
                route.userId(),
                route.name(),
                route.originName(),
                route.destinationName(),
                route.vehicleType(),
                route.distanceMiles(),
                route.durationMinutes(),
                route.climateDelayMinutes(),
                route.riskScore(),
                route.coordinates(),
                route.generatedAt(),
                route.usualDepartureTime(),
                route.riskThreshold(),
                route.monitorEnabled(),
                Instant.now(clock).toString(),
                route.activeHazards(),
                route.riskTrend());
        repository.saveRoute(refreshed);
        repository.recordRouteRiskObservation(RouteRiskObservation.fromRoute(refreshed, "SAVED_ROUTE_MONITOR_REFRESH"));
        idempotencyService.recordResource(context, operation, idempotencyKey, refreshed.savedItemId());
        recordCommand("refresh", "refreshed");
        return toRisk(refreshed);
    }

    public List<RouteRiskPoint> riskHistory(TenantContext context, UUID savedItemId) {
        var route = findOwnedRoute(context, savedItemId);
        var history = repository.findRouteRiskHistory(route.userId(), route.savedItemId(), 30);
        if (history.isEmpty()) {
            return List.of(new RouteRiskPoint(
                    route.lastCheckedAt(),
                    route.riskScore(),
                    route.riskTrend(),
                    route.activeHazards(),
                    "LEGACY_ROUTE_STATE",
                    "route-risk-v1"));
        }
        return history.stream()
                .map(point -> new RouteRiskPoint(
                        point.observedAt(),
                        point.riskScore(),
                        point.riskTrend(),
                        point.activeHazards(),
                        point.source(),
                        point.modelVersion()))
                .toList();
    }

    public void delete(TenantContext context, UUID savedItemId) {
        repository.deleteRoute(context.userId(), savedItemId);
        recordCommand("delete", "deleted");
    }

    private SavedRoute findOwnedRoute(TenantContext context, UUID savedItemId) {
        return repository.findRoute(context.userId(), savedItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Saved route not found"));
    }

    private void recordCommand(String operation, String outcome) {
        meterRegistry.counter(COMMAND_METRIC, "operation", operation, "outcome", outcome).increment();
    }

    private static RouteRiskSnapshot toRisk(SavedRoute route) {
        return new RouteRiskSnapshot(
                route.savedItemId(),
                route.riskScore(),
                route.riskScore() >= route.riskThreshold(),
                route.lastCheckedAt(),
                route.activeHazards(),
                route.riskTrend());
    }

    public record CreateSavedRouteCommand(
            String name,
            String originName,
            String destinationName,
            String vehicleType,
            double distanceMiles,
            double durationMinutes,
            double climateDelayMinutes,
            int riskScore,
            List<List<Double>> coordinates,
            String generatedAt,
            String usualDepartureTime,
            Integer riskThreshold,
            Boolean monitorEnabled,
            List<String> activeHazards) {
    }

    public record UpdateSavedRouteCommand(
            String name,
            String usualDepartureTime,
            Integer riskThreshold,
            Boolean monitorEnabled) {
    }

    public record RouteRiskSnapshot(
            UUID savedItemId,
            int currentRiskScore,
            boolean thresholdExceeded,
            String lastCheckedAt,
            List<String> activeHazards,
            String riskTrend) {
    }

    public record RouteRiskPoint(
            String checkedAt,
            int riskScore,
            String riskTrend,
            List<String> activeHazards,
            String source,
            String modelVersion) {
    }
}
