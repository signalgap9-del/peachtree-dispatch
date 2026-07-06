package com.atmospath.platform.account;

import com.atmospath.platform.relational.SavedPlaceRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/account")
@ConditionalOnProperty(name = "atmospath.auth.enabled", havingValue = "true")
public class AccountController {
    private final TenantContextResolver tenantContextResolver;
    private final EntitlementService entitlements;
    private final SavedPlaceRepository repository;

    public AccountController(
            TenantContextResolver tenantContextResolver,
            EntitlementService entitlements,
            SavedPlaceRepository repository) {
        this.tenantContextResolver = tenantContextResolver;
        this.entitlements = entitlements;
        this.repository = repository;
    }

    @GetMapping
    AccountSummary get(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        var context = tenantContextResolver.resolve(jwt, request);
        var savedRoutes = repository.findRoutes(context.userId()).size();
        var savedPlaces = repository.findAll(context.userId()).size();
        return entitlements.summary(context, savedRoutes, savedPlaces);
    }
}
