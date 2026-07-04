package com.atmospath.platform.relational;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/saved/routes")
@ConditionalOnProperty(name = "atmospath.auth.enabled", havingValue = "true")
public class SavedRouteController {
    private final SavedPlaceRepository repository;

    public SavedRouteController(SavedPlaceRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    List<SavedRoute> list(@AuthenticationPrincipal Jwt jwt) {
        return repository.findRoutes(userId(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SavedRoute create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateSavedRoute request) {
        var route = new SavedRoute(
                UUID.randomUUID(),
                userId(jwt),
                request.name(),
                request.originName(),
                request.destinationName(),
                request.vehicleType(),
                request.distanceMiles(),
                request.durationMinutes(),
                request.climateDelayMinutes(),
                request.riskScore(),
                request.coordinates(),
                request.generatedAt(),
                request.usualDepartureTime() == null ? "08:00" : request.usualDepartureTime(),
                request.riskThreshold() == null ? 55 : request.riskThreshold(),
                request.monitorEnabled() == null || request.monitorEnabled(),
                request.generatedAt(),
                request.activeHazards() == null ? List.of() : request.activeHazards(),
                "STABLE");
        repository.saveRoute(route);
        return route;
    }

    @GetMapping("/{savedItemId}")
    SavedRoute get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID savedItemId) {
        return findOwnedRoute(jwt, savedItemId);
    }

    @PatchMapping("/{savedItemId}")
    SavedRoute update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID savedItemId, @Valid @RequestBody UpdateSavedRoute request) {
        var existing = findOwnedRoute(jwt, savedItemId);
        var updated = new SavedRoute(
                existing.savedItemId(),
                existing.userId(),
                request.name() == null ? existing.name() : request.name(),
                existing.originName(),
                existing.destinationName(),
                existing.vehicleType(),
                existing.distanceMiles(),
                existing.durationMinutes(),
                existing.climateDelayMinutes(),
                existing.riskScore(),
                existing.coordinates(),
                existing.generatedAt(),
                request.usualDepartureTime() == null ? existing.usualDepartureTime() : request.usualDepartureTime(),
                request.riskThreshold() == null ? existing.riskThreshold() : request.riskThreshold(),
                request.monitorEnabled() == null ? existing.monitorEnabled() : request.monitorEnabled(),
                existing.lastCheckedAt(),
                existing.activeHazards(),
                existing.riskTrend());
        repository.saveRoute(updated);
        return updated;
    }

    @GetMapping("/{savedItemId}/current-risk")
    SavedRouteRisk currentRisk(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID savedItemId) {
        var route = findOwnedRoute(jwt, savedItemId);
        return new SavedRouteRisk(
                route.savedItemId(),
                route.riskScore(),
                route.riskScore() >= route.riskThreshold(),
                route.lastCheckedAt(),
                route.activeHazards(),
                route.riskTrend());
    }

    @GetMapping("/{savedItemId}/risk-history")
    List<SavedRouteRiskPoint> riskHistory(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID savedItemId) {
        var route = findOwnedRoute(jwt, savedItemId);
        var history = new java.util.ArrayList<SavedRouteRiskPoint>();
        history.add(new SavedRouteRiskPoint(route.lastCheckedAt(), route.riskScore(), route.riskTrend()));
        repository.findRouteObservations(route.userId(), savedItemId).stream()
                .map(observation -> new SavedRouteRiskPoint(
                        observation.observedAt(),
                        observation.observedRiskScore(),
                        trend(route.riskScore(), observation.observedRiskScore())))
                .forEach(history::add);
        return history;
    }

    @GetMapping("/{savedItemId}/observations")
    List<SavedRouteObservation> observations(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID savedItemId) {
        var route = findOwnedRoute(jwt, savedItemId);
        return repository.findRouteObservations(route.userId(), savedItemId);
    }

    @PostMapping("/{savedItemId}/observations")
    @ResponseStatus(HttpStatus.CREATED)
    SavedRouteObservation createObservation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID savedItemId,
            @Valid @RequestBody CreateRouteObservation request) {
        var route = findOwnedRoute(jwt, savedItemId);
        var plannedDuration = request.plannedDurationMinutes() == null
                ? route.durationMinutes()
                : request.plannedDurationMinutes();
        var observation = new SavedRouteObservation(
                UUID.randomUUID(),
                route.savedItemId(),
                route.userId(),
                request.observedAt() == null ? Instant.now().toString() : request.observedAt(),
                plannedDuration,
                request.actualDurationMinutes(),
                Math.round((request.actualDurationMinutes() - plannedDuration) * 10.0) / 10.0,
                request.observedRiskScore(),
                request.encounteredHazards() == null ? List.of() : request.encounteredHazards(),
                emptyToNull(request.weatherSummary()),
                emptyToNull(request.roadEventSummary()),
                request.source() == null || request.source().isBlank() ? "USER_REPORTED" : request.source(),
                emptyToNull(request.notes()),
                "saved-route-observation-v1");
        repository.saveRouteObservation(observation);
        return observation;
    }

    @GetMapping("/{savedItemId}/ml-dataset")
    List<SavedRouteTrainingExample> mlDataset(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID savedItemId) {
        var route = findOwnedRoute(jwt, savedItemId);
        return repository.findRouteObservations(route.userId(), savedItemId).stream()
                .map(observation -> new SavedRouteTrainingExample(
                        observation.observationId(),
                        route.savedItemId(),
                        observation.featureSchemaVersion(),
                        route.vehicleType(),
                        route.distanceMiles(),
                        observation.plannedDurationMinutes(),
                        route.climateDelayMinutes(),
                        route.riskScore(),
                        route.generatedAt(),
                        observation.observedAt(),
                        observation.actualDurationMinutes(),
                        observation.delayMinutes(),
                        observation.observedRiskScore(),
                        route.activeHazards(),
                        observation.encounteredHazards(),
                        observation.weatherSummary(),
                        observation.roadEventSummary(),
                        observation.source()))
                .toList();
    }

    @DeleteMapping("/{savedItemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID savedItemId) {
        repository.deleteRoute(userId(jwt), savedItemId);
    }

    private UUID userId(Jwt jwt) {
        return repository.ensureUser(jwt.getSubject(), jwt.getClaimAsString("email"));
    }

    private SavedRoute findOwnedRoute(Jwt jwt, UUID savedItemId) {
        return repository.findRoute(userId(jwt), savedItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Saved route not found"));
    }

    record CreateSavedRoute(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 160) String originName,
            @NotBlank @Size(max = 160) String destinationName,
            @NotBlank @Size(max = 24) String vehicleType,
            @DecimalMin("0.1") @DecimalMax("20000") double distanceMiles,
            @DecimalMin("0.1") @DecimalMax("30000") double durationMinutes,
            @DecimalMin("0") @DecimalMax("5000") double climateDelayMinutes,
            @Min(0) @Max(100) int riskScore,
            @NotEmpty @Size(min = 2, max = 1000) List<@Size(min = 2, max = 2) List<Double>> coordinates,
            @Size(max = 64) String generatedAt,
            @Size(max = 16) String usualDepartureTime,
            @Min(0) @Max(100) Integer riskThreshold,
            Boolean monitorEnabled,
            @Size(max = 12) List<@Size(max = 64) String> activeHazards) {
    }

    record UpdateSavedRoute(
            @Size(max = 160) String name,
            @Size(max = 16) String usualDepartureTime,
            @Min(0) @Max(100) Integer riskThreshold,
            Boolean monitorEnabled) {
    }

    record CreateRouteObservation(
            @Size(max = 64) String observedAt,
            @DecimalMin("0.1") @DecimalMax("30000") double actualDurationMinutes,
            @DecimalMin("0.1") @DecimalMax("30000") Double plannedDurationMinutes,
            @Min(0) @Max(100) int observedRiskScore,
            @Size(max = 12) List<@Size(max = 80) String> encounteredHazards,
            @Size(max = 240) String weatherSummary,
            @Size(max = 240) String roadEventSummary,
            @Size(max = 64) String source,
            @Size(max = 500) String notes) {
    }

    record SavedRouteRisk(
            UUID savedItemId,
            int currentRiskScore,
            boolean thresholdExceeded,
            String lastCheckedAt,
            List<String> activeHazards,
            String riskTrend) {
    }

    record SavedRouteRiskPoint(
            String checkedAt,
            int riskScore,
            String riskTrend) {
    }

    private static String trend(int baseline, int observed) {
        if (observed >= baseline + 10) return "WORSENING";
        if (observed <= baseline - 10) return "IMPROVING";
        return "STABLE";
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
