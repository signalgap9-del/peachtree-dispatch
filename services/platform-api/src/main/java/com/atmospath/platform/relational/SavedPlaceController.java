package com.atmospath.platform.relational;

import java.util.List;
import java.util.UUID;

import com.atmospath.platform.account.EntitlementService;
import com.atmospath.platform.account.TenantContextResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/saved/places")
@ConditionalOnProperty(name = "atmospath.auth.enabled", havingValue = "true")
public class SavedPlaceController {
    private final SavedPlaceRepository repository;
    private final TenantContextResolver tenantContextResolver;
    private final EntitlementService entitlements;

    public SavedPlaceController(
            SavedPlaceRepository repository,
            TenantContextResolver tenantContextResolver,
            EntitlementService entitlements) {
        this.repository = repository;
        this.tenantContextResolver = tenantContextResolver;
        this.entitlements = entitlements;
    }

    @GetMapping
    List<SavedPlace> list(@AuthenticationPrincipal Jwt jwt) {
        return repository.findAll(userId(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SavedPlace create(
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest servletRequest,
            @Valid @RequestBody CreateSavedPlace request) {
        var context = tenantContextResolver.resolve(jwt, servletRequest);
        entitlements.requireSavedPlaceCapacity(context, repository.findAll(context.userId()).size());
        var place = new SavedPlace(
                UUID.randomUUID(),
                context.userId(),
                request.name(),
                request.longitude(),
                request.latitude(),
                request.currentRiskScore());
        repository.save(place);
        return place;
    }

    @DeleteMapping("/{savedItemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID savedItemId) {
        repository.delete(userId(jwt), savedItemId);
    }

    private UUID userId(Jwt jwt) {
        return repository.ensureUser(jwt.getSubject(), jwt.getClaimAsString("email"));
    }

    record CreateSavedPlace(
            @NotBlank @Size(max = 160) String name,
            @DecimalMin("-180") @DecimalMax("180") double longitude,
            @DecimalMin("-90") @DecimalMax("90") double latitude,
            @Min(0) @Max(100) Integer currentRiskScore) {
    }
}
