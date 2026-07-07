package com.atmospath.platform.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import com.atmospath.platform.relational.SavedPlaceRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class TenantContextResolver {
    private final Optional<SavedPlaceRepository> repository;
    private final PlanCode defaultPlan;

    @Autowired
    public TenantContextResolver(
            ObjectProvider<SavedPlaceRepository> repositoryProvider,
            @Value("${atmospath.default-plan:FREE}") PlanCode defaultPlan) {
        this(Optional.ofNullable(repositoryProvider.getIfAvailable()), defaultPlan);
    }

    TenantContextResolver(Optional<SavedPlaceRepository> repository, PlanCode defaultPlan) {
        this.repository = repository;
        this.defaultPlan = defaultPlan;
    }

    public TenantContext resolve(Jwt jwt, HttpServletRequest request) {
        if (jwt != null) {
            return resolveAuthenticated(jwt);
        }
        return resolveAnonymous(request);
    }

    private TenantContext resolveAuthenticated(Jwt jwt) {
        var subject = jwt.getSubject();
        var email = safe(jwt.getClaimAsString("email"));
        var userId = repository
                .map(store -> store.ensureUser(subject, email))
                .orElseGet(() -> stableUuid("user:" + subject));
        return new TenantContext(
                stableUuid("tenant:" + subject),
                userId,
                subject,
                email,
                planFromJwt(jwt),
                SubscriptionStatus.ACTIVE,
                roleFromJwt(jwt),
                true);
    }

    private TenantContext resolveAnonymous(HttpServletRequest request) {
        var fingerprint = sha256(safe(request.getRemoteAddr()) + "|" + safe(request.getHeader("User-Agent")));
        var subject = "anonymous:" + fingerprint.substring(0, 24);
        return new TenantContext(
                stableUuid("tenant:" + subject),
                null,
                subject,
                "",
                PlanCode.FREE,
                SubscriptionStatus.TRIALING,
                TenantRole.VIEWER,
                false);
    }

    private PlanCode planFromJwt(Jwt jwt) {
        var plan = firstNonBlank(jwt.getClaimAsString("custom:plan"), jwt.getClaimAsString("plan"));
        if (plan == null) {
            return defaultPlan;
        }
        try {
            return PlanCode.valueOf(plan.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return defaultPlan;
        }
    }

    private TenantRole roleFromJwt(Jwt jwt) {
        var role = firstNonBlank(jwt.getClaimAsString("custom:tenant_role"), jwt.getClaimAsString("tenant_role"));
        if (role == null) {
            return TenantRole.OWNER;
        }
        try {
            return TenantRole.valueOf(role.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return TenantRole.OWNER;
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private static UUID stableUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available.", exception);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
