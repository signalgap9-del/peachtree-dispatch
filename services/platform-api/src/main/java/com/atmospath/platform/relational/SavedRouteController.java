package com.atmospath.platform.relational;

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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
                request.generatedAt());
        repository.saveRoute(route);
        return route;
    }

    @DeleteMapping("/{savedItemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID savedItemId) {
        repository.deleteRoute(userId(jwt), savedItemId);
    }

    private UUID userId(Jwt jwt) {
        return repository.ensureUser(jwt.getSubject(), jwt.getClaimAsString("email"));
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
            @Size(max = 64) String generatedAt) {
    }
}
