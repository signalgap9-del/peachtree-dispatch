package com.atmospath.platform.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;

class TenantContextResolverTests {
    @Test
    void resolvesAnonymousRequestsToStableFreeTenantWithoutPii() {
        var resolver = new TenantContextResolver(Optional.empty(), PlanCode.PRO);
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("User-Agent", "Playwright");

        var first = resolver.resolve(null, request);
        var second = resolver.resolve(null, request);

        assertThat(first.tenantId()).isEqualTo(second.tenantId());
        assertThat(first.subject()).startsWith("anonymous:");
        assertThat(first.email()).isBlank();
        assertThat(first.plan()).isEqualTo(PlanCode.FREE);
        assertThat(first.authenticated()).isFalse();
    }

    @Test
    void resolvesAuthenticatedPlanFromJwtClaim() {
        var resolver = new TenantContextResolver(Optional.empty(), PlanCode.FREE);
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("google-oauth2|123")
                .claim("email", "driver@example.com")
                .claim("custom:plan", "team")
                .issuedAt(Instant.parse("2026-07-04T00:00:00Z"))
                .expiresAt(Instant.parse("2026-07-04T01:00:00Z"))
                .claims(claims -> claims.putAll(Map.of("scope", "openid")))
                .build();

        var context = resolver.resolve(jwt, new MockHttpServletRequest());

        assertThat(context.authenticated()).isTrue();
        assertThat(context.email()).isEqualTo("driver@example.com");
        assertThat(context.plan()).isEqualTo(PlanCode.TEAM);
        assertThat(context.userId()).isNotNull();
        assertThat(context.tenantId()).isNotNull();
    }
}
