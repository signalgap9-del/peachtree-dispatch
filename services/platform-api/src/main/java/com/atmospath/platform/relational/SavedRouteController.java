package com.atmospath.platform.relational;

import java.util.List;
import java.util.UUID;

import com.atmospath.platform.account.EntitlementService;
import com.atmospath.platform.account.TenantAuthorizationService;
import com.atmospath.platform.account.TenantContextResolver;
import jakarta.servlet.http.HttpServletRequest;
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
    private final TenantContextResolver tenantContextResolver;
    private final EntitlementService entitlements;
    private final TenantAuthorizationService tenantAuthorization;

    public SavedRouteController(
            SavedPlaceRepository repository,
            TenantContextResolver tenantContextResolver,
            EntitlementService entitlements,
            TenantAuthorizationService tenantAuthorization) {
        this.repository = repository;
        this.tenantContextResolver = tenantContextResolver;
        this.entitlements = entitlements;
        this.tenantAuthorization = tenantAuthorization;
    }

    @GetMapping
    List<SavedRoute> list(@AuthenticationPrincipal Jwt jwt, HttpServletRequest servletRequest) {
        var context = tenantContextResolver.resolve(jwt, servletRequest);
        tenantAuthorization.requireSavedAssetAccess(context);
        return repository.findRoutes(context.userId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SavedRoute create(
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest servletRequest,
            @Valid @RequestBody CreateSavedRoute request) {
        var context = tenantContextResolver.resolve(jwt, servletRequest);
        tenantAuthorization.requireSavedAssetAccess(context);
        entitlements.requireSavedRouteCapacity(context, repository.findRoutes(context.userId()).size());
        var route = new SavedRoute(
                UUID.randomUUID(),
                context.userId(),
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
        repository.recordRouteRiskObservation(RouteRiskObservation.fromRoute(route, "SAVED_ROUTE_CREATED"));
        return route;
    }

    @GetMapping("/{savedItemId}")
    SavedRoute get(@AuthenticationPrincipal Jwt jwt, HttpServletRequest servletRequest, @PathVariable UUID savedItemId) {
        return findOwnedRoute(jwt, servletRequest, savedItemId);
    }

    @PatchMapping("/{savedItemId}")
    SavedRoute update(
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest servletRequest,
            @PathVariable UUID savedItemId,
            @Valid @RequestBody UpdateSavedRoute request) {
        var existing = findOwnedRoute(jwt, servletRequest, savedItemId);
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
        repository.recordRouteRiskObservation(RouteRiskObservation.fromRoute(updated, "SAVED_ROUTE_UPDATED"));
        return updated;
    }

    @GetMapping("/{savedItemId}/current-risk")
    SavedRouteRisk currentRisk(@AuthenticationPrincipal Jwt jwt, HttpServletRequest servletRequest, @PathVariable UUID savedItemId) {
        var route = findOwnedRoute(jwt, servletRequest, savedItemId);
        return new SavedRouteRisk(
                route.savedItemId(),
                route.riskScore(),
                route.riskScore() >= route.riskThreshold(),
                route.lastCheckedAt(),
                route.activeHazards(),
                route.riskTrend());
    }

    @GetMapping("/{savedItemId}/risk-history")
    List<SavedRouteRiskPoint> riskHistory(@AuthenticationPrincipal Jwt jwt, HttpServletRequest servletRequest, @PathVariable UUID savedItemId) {
        var route = findOwnedRoute(jwt, servletRequest, savedItemId);
        var history = repository.findRouteRiskHistory(route.userId(), route.savedItemId(), 30);
        if (history.isEmpty()) {
            return List.of(new SavedRouteRiskPoint(
                    route.lastCheckedAt(),
                    route.riskScore(),
                    route.riskTrend(),
                    route.activeHazards(),
                    "LEGACY_ROUTE_STATE",
                    "route-risk-v1"));
        }
        return history.stream()
                .map(point -> new SavedRouteRiskPoint(
                        point.observedAt(),
                        point.riskScore(),
                        point.riskTrend(),
                        point.activeHazards(),
                        point.source(),
                        point.modelVersion()))
                .toList();
    }

    @DeleteMapping("/{savedItemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, HttpServletRequest servletRequest, @PathVariable UUID savedItemId) {
        var context = tenantContextResolver.resolve(jwt, servletRequest);
        tenantAuthorization.requireSavedAssetAccess(context);
        repository.deleteRoute(context.userId(), savedItemId);
    }

    private SavedRoute findOwnedRoute(Jwt jwt, HttpServletRequest servletRequest, UUID savedItemId) {
        var context = tenantContextResolver.resolve(jwt, servletRequest);
        tenantAuthorization.requireSavedAssetAccess(context);
        return repository.findRoute(context.userId(), savedItemId)
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
            String riskTrend,
            List<String> activeHazards,
            String source,
            String modelVersion) {
    }
}
