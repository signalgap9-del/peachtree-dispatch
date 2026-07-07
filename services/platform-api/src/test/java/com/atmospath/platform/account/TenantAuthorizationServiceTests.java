package com.atmospath.platform.account;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class TenantAuthorizationServiceTests {
    private final TenantAuthorizationService service = new TenantAuthorizationService();

    @Test
    void rejectsAnonymousSavedAssetAccess() {
        var context = new TenantContext(
                UUID.randomUUID(),
                null,
                "anonymous:test",
                "",
                PlanCode.FREE,
                SubscriptionStatus.TRIALING,
                TenantRole.VIEWER,
                false);

        assertThatThrownBy(() -> service.requireSavedAssetAccess(context))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");
    }

    @Test
    void rejectsViewerSavedAssetAccessEvenWhenAuthenticated() {
        var context = new TenantContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "subject",
                "person@example.com",
                PlanCode.FREE,
                SubscriptionStatus.ACTIVE,
                TenantRole.VIEWER,
                true);

        assertThatThrownBy(() -> service.requireSavedAssetAccess(context))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }
}
