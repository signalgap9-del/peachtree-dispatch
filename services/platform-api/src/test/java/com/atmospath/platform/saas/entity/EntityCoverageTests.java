package com.atmospath.platform.saas.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Exercises constructors, getters, setters, and JPA lifecycle callbacks
 * for SaaS entity classes to ensure full coverage of their accessors.
 */
class EntityCoverageTests {

    @Test
    void apiKeyRecordFullLifecycle() {
        UUID tenantId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(3600);
        var key = new ApiKeyRecord(tenantId, memberId, "sha256hash", "CI key", expiry);
        key.onCreate();

        assertThat(key.getId()).isNotNull();
        assertThat(key.getTenantId()).isEqualTo(tenantId);
        assertThat(key.getMemberId()).isEqualTo(memberId);
        assertThat(key.getKeyHash()).isEqualTo("sha256hash");
        assertThat(key.getName()).isEqualTo("CI key");
        assertThat(key.getExpiresAt()).isEqualTo(expiry);
        assertThat(key.getCreatedAt()).isNotNull();
        assertThat(key.getLastUsedAt()).isNull();
        assertThat(key.isActive(Instant.now())).isTrue();

        key.setName("renamed");
        key.setLastUsedAt(Instant.now());
        key.setExpiresAt(Instant.now().minusSeconds(1));
        assertThat(key.getName()).isEqualTo("renamed");
        assertThat(key.getLastUsedAt()).isNotNull();
        assertThat(key.isActive(Instant.now())).isFalse();

        // No expiry means always active
        var noExpiry = new ApiKeyRecord(tenantId, memberId, "hash2", "key2", null);
        assertThat(noExpiry.isActive(Instant.now())).isTrue();
    }

    @Test
    void tenantMemberFullLifecycle() {
        UUID tenantId = UUID.randomUUID();
        var member = new TenantMember(tenantId, "a@b.com", "Alice", MemberRole.ADMIN);
        member.onCreate();

        assertThat(member.getId()).isNotNull();
        assertThat(member.getTenantId()).isEqualTo(tenantId);
        assertThat(member.getEmail()).isEqualTo("a@b.com");
        assertThat(member.getDisplayName()).isEqualTo("Alice");
        assertThat(member.getRole()).isEqualTo(MemberRole.ADMIN);
        assertThat(member.getCreatedAt()).isNotNull();
        assertThat(member.getUpdatedAt()).isNotNull();
        assertThat(member.getDeletedAt()).isNull();

        member.setEmail("new@b.com");
        member.setDisplayName("Bob");
        member.setRole(MemberRole.OWNER);
        member.setDeletedAt(Instant.now());
        member.onUpdate();

        assertThat(member.getEmail()).isEqualTo("new@b.com");
        assertThat(member.getDisplayName()).isEqualTo("Bob");
        assertThat(member.getRole()).isEqualTo(MemberRole.OWNER);
        assertThat(member.getDeletedAt()).isNotNull();
    }

    @Test
    void savedPlaceEntityFullLifecycle() {
        UUID memberId = UUID.randomUUID();
        UUID wsId = UUID.randomUUID();
        var place = new SavedPlaceEntity(memberId, wsId, "Home", 37.5, 127.0);
        place.onCreate();

        assertThat(place.getId()).isNotNull();
        assertThat(place.getMemberId()).isEqualTo(memberId);
        assertThat(place.getWorkspaceId()).isEqualTo(wsId);
        assertThat(place.getName()).isEqualTo("Home");
        assertThat(place.getLatitude()).isEqualTo(37.5);
        assertThat(place.getLongitude()).isEqualTo(127.0);
        assertThat(place.getCreatedAt()).isNotNull();
        assertThat(place.getDeletedAt()).isNull();

        place.setName("Office");
        place.setLatitude(35.1);
        place.setLongitude(129.0);
        place.setWorkspaceId(UUID.randomUUID());
        place.setDeletedAt(Instant.now());

        assertThat(place.getName()).isEqualTo("Office");
        assertThat(place.getLatitude()).isEqualTo(35.1);
        assertThat(place.getLongitude()).isEqualTo(129.0);
        assertThat(place.getDeletedAt()).isNotNull();
    }

    @Test
    void savedRouteEntityFullLifecycle() {
        UUID memberId = UUID.randomUUID();
        UUID wsId = UUID.randomUUID();
        var route = new SavedRouteEntity(memberId, wsId, "Commute");
        route.onCreate();

        assertThat(route.getId()).isNotNull();
        assertThat(route.getMemberId()).isEqualTo(memberId);
        assertThat(route.getWorkspaceId()).isEqualTo(wsId);
        assertThat(route.getName()).isEqualTo("Commute");
        assertThat(route.getVehicleType()).isEqualTo("car");
        assertThat(route.getRiskThreshold()).isEqualTo(55);
        assertThat(route.isMonitorEnabled()).isTrue();
        assertThat(route.getCreatedAt()).isNotNull();
        assertThat(route.getUpdatedAt()).isNotNull();
        assertThat(route.getDeletedAt()).isNull();
        assertThat(route.getOriginName()).isNull();
        assertThat(route.getDestinationName()).isNull();

        route.setName("Work");
        route.setOriginName("Home");
        route.setDestinationName("Office");
        route.setVehicleType("truck");
        route.setRiskThreshold(70);
        route.setMonitorEnabled(false);
        route.setWorkspaceId(UUID.randomUUID());
        route.setDeletedAt(Instant.now());
        route.onUpdate();

        assertThat(route.getName()).isEqualTo("Work");
        assertThat(route.getOriginName()).isEqualTo("Home");
        assertThat(route.getDestinationName()).isEqualTo("Office");
        assertThat(route.getVehicleType()).isEqualTo("truck");
        assertThat(route.getRiskThreshold()).isEqualTo(70);
        assertThat(route.isMonitorEnabled()).isFalse();
        assertThat(route.getDeletedAt()).isNotNull();
    }

    @Test
    void entitlementFullLifecycle() {
        UUID subId = UUID.randomUUID();
        var ent = new Entitlement(subId, "risk_api", 1000, 10, 20);
        ent.onCreate();

        assertThat(ent.getId()).isNotNull();
        assertThat(ent.getSubscriptionId()).isEqualTo(subId);
        assertThat(ent.getFeature()).isEqualTo("risk_api");
        assertThat(ent.getQuotaLimit()).isEqualTo(1000);
        assertThat(ent.getSavedRouteLimit()).isEqualTo(10);
        assertThat(ent.getSavedPlaceLimit()).isEqualTo(20);

        ent.setQuotaLimit(2000);
        ent.setSavedRouteLimit(50);
        ent.setSavedPlaceLimit(100);
        assertThat(ent.getQuotaLimit()).isEqualTo(2000);
        assertThat(ent.getSavedRouteLimit()).isEqualTo(50);
        assertThat(ent.getSavedPlaceLimit()).isEqualTo(100);
    }

    @Test
    void tenantFullLifecycle() {
        var tenant = new Tenant("Acme Corp", "acme");
        tenant.onCreate();

        assertThat(tenant.getId()).isNotNull();
        assertThat(tenant.getName()).isEqualTo("Acme Corp");
        assertThat(tenant.getSlug()).isEqualTo("acme");
        assertThat(tenant.getCreatedAt()).isNotNull();
        assertThat(tenant.getUpdatedAt()).isNotNull();

        tenant.setName("Acme Inc");
        tenant.setSlug("acme-inc");
        tenant.onUpdate();
        assertThat(tenant.getName()).isEqualTo("Acme Inc");
        assertThat(tenant.getSlug()).isEqualTo("acme-inc");
    }

    @Test
    void workspaceFullLifecycle() {
        UUID tenantId = UUID.randomUUID();
        var ws = new Workspace(tenantId, "Default");
        ws.onCreate();

        assertThat(ws.getId()).isNotNull();
        assertThat(ws.getTenantId()).isEqualTo(tenantId);
        assertThat(ws.getName()).isEqualTo("Default");
        assertThat(ws.getCreatedAt()).isNotNull();
        assertThat(ws.getUpdatedAt()).isNotNull();

        ws.setName("Ops");
        ws.onUpdate();
        assertThat(ws.getName()).isEqualTo("Ops");
    }

    @Test
    void workspaceMemberFullLifecycle() {
        UUID wsId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        var wm = new WorkspaceMember(wsId, memberId, WorkspaceRole.EDITOR);
        wm.onCreate();

        assertThat(wm.getId()).isNotNull();
        assertThat(wm.getWorkspaceId()).isEqualTo(wsId);
        assertThat(wm.getMemberId()).isEqualTo(memberId);
        assertThat(wm.getRole()).isEqualTo(WorkspaceRole.EDITOR);
        assertThat(wm.getCreatedAt()).isNotNull();

        wm.setRole(WorkspaceRole.VIEWER);
        assertThat(wm.getRole()).isEqualTo(WorkspaceRole.VIEWER);
    }

    @Test
    void idempotencyKeyRecordFullLifecycle() {
        UUID tenantId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(86400);
        var record = new IdempotencyKeyRecord(tenantId, "create_route", "hash123", resourceId, expiry);
        record.onCreate();

        assertThat(record.getTenantId()).isEqualTo(tenantId);
        assertThat(record.getOperation()).isEqualTo("create_route");
        assertThat(record.getKeyHash()).isEqualTo("hash123");
        assertThat(record.getResourceId()).isEqualTo(resourceId);
        assertThat(record.getCreatedAt()).isNotNull();
        assertThat(record.getExpiresAt()).isEqualTo(expiry);
        assertThat(record.isExpired(Instant.now())).isFalse();
        assertThat(record.isExpired(Instant.now().plusSeconds(90000))).isTrue();

        UUID newId = UUID.randomUUID();
        record.setResourceId(newId);
        assertThat(record.getResourceId()).isEqualTo(newId);
    }

    @Test
    void alertEscalationFullLifecycle() {
        UUID alertId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        var esc = new AlertEscalation(alertId, 2, memberId);
        esc.onCreate();

        assertThat(esc.getId()).isNotNull();
        assertThat(esc.getAlertEventId()).isEqualTo(alertId);
        assertThat(esc.getLevel()).isEqualTo(2);
        assertThat(esc.getTargetMemberId()).isEqualTo(memberId);
        assertThat(esc.getNotifiedAt()).isNotNull();
        assertThat(esc.getRespondedAt()).isNull();

        Instant responded = Instant.now();
        esc.setRespondedAt(responded);
        assertThat(esc.getRespondedAt()).isEqualTo(responded);
    }

    @Test
    void auditLogFullLifecycle() {
        UUID tenantId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        var log = new AuditLog(tenantId, memberId, "UPDATE", "saved_route",
                resourceId, "{\"name\":\"old\"}");
        log.onCreate();

        assertThat(log.getTenantId()).isEqualTo(tenantId);
        assertThat(log.getMemberId()).isEqualTo(memberId);
        assertThat(log.getAction()).isEqualTo("UPDATE");
        assertThat(log.getResourceType()).isEqualTo("saved_route");
        assertThat(log.getResourceId()).isEqualTo(resourceId);
        assertThat(log.getChanges()).contains("old");
        assertThat(log.getCreatedAt()).isNotNull();
    }

    @Test
    void alertEventFullLifecycle() {
        UUID routeId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        var alert = new AlertEvent(routeId, tenantId, AlertSeverity.SEVERE, 95);
        alert.onCreate();

        assertThat(alert.getId()).isNotNull();
        assertThat(alert.getSavedRouteId()).isEqualTo(routeId);
        assertThat(alert.getTenantId()).isEqualTo(tenantId);
        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.SEVERE);
        assertThat(alert.getRiskScore()).isEqualTo(95);
        assertThat(alert.getState()).isEqualTo(AlertState.TRIGGERED);
        assertThat(alert.getTriggeredAt()).isNotNull();
        assertThat(alert.getTriggeredBy()).isEqualTo("SYSTEM");
        assertThat(alert.getNotifiedAt()).isNull();
        assertThat(alert.getEscalatedAt()).isNull();
        assertThat(alert.getAcknowledgedAt()).isNull();
        assertThat(alert.getResolvedAt()).isNull();

        Instant now = Instant.now();
        alert.setState(AlertState.ACKNOWLEDGED);
        alert.setRiskScore(80);
        alert.setNotifiedAt(now);
        alert.setEscalatedAt(now);
        alert.setAcknowledgedAt(now);
        alert.setResolvedAt(now);
        alert.setTriggeredBy("USER");

        assertThat(alert.getState()).isEqualTo(AlertState.ACKNOWLEDGED);
        assertThat(alert.getRiskScore()).isEqualTo(80);
        assertThat(alert.getNotifiedAt()).isEqualTo(now);
        assertThat(alert.getEscalatedAt()).isEqualTo(now);
        assertThat(alert.getAcknowledgedAt()).isEqualTo(now);
        assertThat(alert.getResolvedAt()).isEqualTo(now);
        assertThat(alert.getTriggeredBy()).isEqualTo("USER");
    }

    @Test
    void memberRoleHierarchy() {
        assertThat(MemberRole.OWNER.atLeast(MemberRole.MEMBER)).isTrue();
        assertThat(MemberRole.OWNER.atLeast(MemberRole.ADMIN)).isTrue();
        assertThat(MemberRole.OWNER.atLeast(MemberRole.OWNER)).isTrue();
        assertThat(MemberRole.MEMBER.atLeast(MemberRole.ADMIN)).isFalse();
        assertThat(MemberRole.ADMIN.atLeast(MemberRole.OWNER)).isFalse();
        assertThat(MemberRole.ADMIN.rank()).isEqualTo(2);
    }

    @Test
    void alertStateClosedCheck() {
        assertThat(AlertState.ACKNOWLEDGED.closed()).isTrue();
        assertThat(AlertState.RESOLVED.closed()).isTrue();
        assertThat(AlertState.TRIGGERED.closed()).isFalse();
        assertThat(AlertState.NOTIFIED.closed()).isFalse();
        assertThat(AlertState.ESCALATED.closed()).isFalse();
    }

    @Test
    void routeRiskObservationEntityAccessors() {
        Instant time = Instant.now();
        UUID routeId = UUID.randomUUID();
        var obs = new RouteRiskObservationEntity(time, routeId, 72, "HIGH", "{\"wind\":0.5}");

        assertThat(obs.getTime()).isEqualTo(time);
        assertThat(obs.getSavedRouteId()).isEqualTo(routeId);
        assertThat(obs.getRiskScore()).isEqualTo(72);
        assertThat(obs.getRiskLevel()).isEqualTo("HIGH");
        assertThat(obs.getFactors()).contains("wind");

        obs.setFactors("{\"ice\":0.9}");
        assertThat(obs.getFactors()).contains("ice");
    }

    @Test
    void subscriptionFullLifecycle() {
        UUID tenantId = UUID.randomUUID();
        LocalDate start = LocalDate.of(2025, 1, 1);
        LocalDate end = LocalDate.of(2025, 12, 31);
        var sub = new Subscription(tenantId, SubscriptionPlan.PRO,
                SubscriptionState.ACTIVE, start, end);
        sub.onCreate();

        assertThat(sub.getId()).isNotNull();
        assertThat(sub.getTenantId()).isEqualTo(tenantId);
        assertThat(sub.getPlan()).isEqualTo(SubscriptionPlan.PRO);
        assertThat(sub.getStatus()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(sub.getCurrentPeriodStart()).isEqualTo(start);
        assertThat(sub.getCurrentPeriodEnd()).isEqualTo(end);
        assertThat(sub.getValidFrom()).isNotNull();
        assertThat(sub.getValidTo()).isEqualTo(Subscription.VALID_FOREVER);
        assertThat(sub.getCreatedAt()).isNotNull();
        assertThat(sub.isOpen()).isTrue();
        assertThat(sub.getVersion()).isNull();

        sub.setPlan(SubscriptionPlan.STARTER);
        sub.setStatus(SubscriptionState.CANCELLED);
        sub.setCurrentPeriodStart(LocalDate.of(2026, 1, 1));
        sub.setCurrentPeriodEnd(LocalDate.of(2026, 6, 30));
        sub.setValidFrom(Instant.now());
        sub.setValidTo(Instant.now().plusSeconds(3600));
        sub.setLemonsqueezySubscriptionId("ls-sub-1");
        sub.setLemonsqueezyCustomerId("ls-cust-1");
        sub.setLemonsqueezyVariantId("ls-var-1");
        sub.setBillingStatus("past_due");
        sub.setBillingPeriodEnd(Instant.now());
        sub.setCancelAtPeriodEnd(true);

        assertThat(sub.getPlan()).isEqualTo(SubscriptionPlan.STARTER);
        assertThat(sub.getStatus()).isEqualTo(SubscriptionState.CANCELLED);
        assertThat(sub.getLemonsqueezySubscriptionId()).isEqualTo("ls-sub-1");
        assertThat(sub.getLemonsqueezyCustomerId()).isEqualTo("ls-cust-1");
        assertThat(sub.getLemonsqueezyVariantId()).isEqualTo("ls-var-1");
        assertThat(sub.getBillingStatus()).isEqualTo("past_due");
        assertThat(sub.getBillingPeriodEnd()).isNotNull();
        assertThat(sub.getCancelAtPeriodEnd()).isTrue();
        assertThat(sub.isOpen()).isFalse();
    }
}
