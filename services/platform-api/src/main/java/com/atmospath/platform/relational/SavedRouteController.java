package com.atmospath.platform.relational;

import java.util.List;
import java.util.UUID;

import com.atmospath.platform.account.TenantAuthorizationService;
import com.atmospath.platform.account.TenantContext;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/saved/routes")
@ConditionalOnProperty(name = "atmospath.auth.enabled", havingValue = "true")
public class SavedRouteController {
    private final SavedRouteService routeService;
    private final TenantContextResolver tenantContextResolver;
    private final TenantAuthorizationService tenantAuthorization;

    public SavedRouteController(
            SavedRouteService routeService,
            TenantContextResolver tenantContextResolver,
            TenantAuthorizationService tenantAuthorization) {
        this.routeService = routeService;
        this.tenantContextResolver = tenantContextResolver;
        this.tenantAuthorization = tenantAuthorization;
    }

    @GetMapping
    List<SavedRoute> list(@AuthenticationPrincipal Jwt jwt, HttpServletRequest servletRequest) {
        var context = tenantContextResolver.resolve(jwt, servletRequest);
        tenantAuthorization.requireSavedAssetAccess(context);
        return routeService.list(context);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SavedRoute create(
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest servletRequest,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateSavedRoute request) {
        var context = tenantContextResolver.resolve(jwt, servletRequest);
        tenantAuthorization.requireSavedAssetAccess(context);
        return routeService.create(context, new SavedRouteService.CreateSavedRouteCommand(
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
                request.usualDepartureTime(),
                request.riskThreshold(),
                request.monitorEnabled(),
                request.activeHazards()), idempotencyKey);
    }

    @GetMapping("/{savedItemId}")
    SavedRoute get(@AuthenticationPrincipal Jwt jwt, HttpServletRequest servletRequest, @PathVariable UUID savedItemId) {
        var context = authorizedContext(jwt, servletRequest);
        return routeService.get(context, savedItemId);
    }

    @PatchMapping("/{savedItemId}")
    SavedRoute update(
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest servletRequest,
            @PathVariable UUID savedItemId,
            @Valid @RequestBody UpdateSavedRoute request) {
        var context = authorizedContext(jwt, servletRequest);
        return routeService.update(context, savedItemId, new SavedRouteService.UpdateSavedRouteCommand(
                request.name(),
                request.usualDepartureTime(),
                request.riskThreshold(),
                request.monitorEnabled()));
    }

    @GetMapping("/{savedItemId}/current-risk")
    SavedRouteService.RouteRiskSnapshot currentRisk(
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest servletRequest,
            @PathVariable UUID savedItemId) {
        var context = authorizedContext(jwt, servletRequest);
        return routeService.currentRisk(context, savedItemId);
    }

    @PostMapping("/{savedItemId}/risk-refresh")
    SavedRouteService.RouteRiskSnapshot refreshRisk(
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest servletRequest,
            @PathVariable UUID savedItemId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        var context = authorizedContext(jwt, servletRequest);
        return routeService.refreshRisk(context, savedItemId, idempotencyKey);
    }

    @GetMapping("/{savedItemId}/risk-history")
    List<SavedRouteService.RouteRiskPoint> riskHistory(
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest servletRequest,
            @PathVariable UUID savedItemId) {
        var context = authorizedContext(jwt, servletRequest);
        return routeService.riskHistory(context, savedItemId);
    }

    @DeleteMapping("/{savedItemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, HttpServletRequest servletRequest, @PathVariable UUID savedItemId) {
        var context = tenantContextResolver.resolve(jwt, servletRequest);
        tenantAuthorization.requireSavedAssetAccess(context);
        routeService.delete(context, savedItemId);
    }

    private TenantContext authorizedContext(Jwt jwt, HttpServletRequest servletRequest) {
        var context = tenantContextResolver.resolve(jwt, servletRequest);
        tenantAuthorization.requireSavedAssetAccess(context);
        return context;
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

}
