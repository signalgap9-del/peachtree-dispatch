package com.atmospath.platform.saas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.atmospath.platform.saas.entity.AlertEscalation;
import com.atmospath.platform.saas.entity.AlertEvent;
import com.atmospath.platform.saas.entity.AlertSeverity;
import com.atmospath.platform.saas.entity.AlertState;
import com.atmospath.platform.saas.entity.MemberRole;
import com.atmospath.platform.saas.entity.SavedRouteEntity;
import com.atmospath.platform.saas.entity.TenantMember;
import com.atmospath.platform.saas.redis.RedisAlertPubSub;
import com.atmospath.platform.saas.repository.AlertEscalationRepository;
import com.atmospath.platform.saas.repository.AlertEventRepository;
import com.atmospath.platform.saas.repository.SavedRouteRepository;
import com.atmospath.platform.saas.repository.TenantMemberRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AlertEscalationServiceTests {

    private static final UUID ROUTE_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID MEMBER_ID = UUID.randomUUID();

    private AlertEventRepository alertEventRepo;
    private AlertEscalationRepository alertEscalationRepo;
    private SavedRouteRepository savedRouteRepo;
    private TenantMemberRepository tenantMemberRepo;
    private RedisAlertPubSub redisPubSub;
    private EntityManager entityManager;
    private AlertEscalationService service;

    @BeforeEach
    void setUp() {
        alertEventRepo = mock(AlertEventRepository.class);
        alertEscalationRepo = mock(AlertEscalationRepository.class);
        savedRouteRepo = mock(SavedRouteRepository.class);
        tenantMemberRepo = mock(TenantMemberRepository.class);
        redisPubSub = mock(RedisAlertPubSub.class);
        entityManager = mock(EntityManager.class);
        service = new AlertEscalationService(
                alertEventRepo, alertEscalationRepo, savedRouteRepo,
                tenantMemberRepo, redisPubSub, entityManager);
    }

    private SavedRouteEntity routeWithMember() {
        SavedRouteEntity route = new SavedRouteEntity(MEMBER_ID, null, "Test Route");
        ReflectionTestUtils.setField(route, "id", ROUTE_ID);
        return route;
    }

    private TenantMember tenantMember() {
        TenantMember member = mock(TenantMember.class);
        when(member.getTenantId()).thenReturn(TENANT_ID);
        when(member.getId()).thenReturn(MEMBER_ID);
        when(member.getRole()).thenReturn(MemberRole.ADMIN);
        return member;
    }

    @Test
    void triggerAlertCreatesEventAndNotifies() {
        when(savedRouteRepo.findById(ROUTE_ID)).thenReturn(Optional.of(routeWithMember()));
        TenantMember member = tenantMember();
        when(tenantMemberRepo.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(alertEventRepo.transitionState(any(), eq("NOTIFIED"))).thenReturn(true);

        AlertEvent event = service.triggerAlert(ROUTE_ID, 85, AlertSeverity.SEVERE);

        assertThat(event.getSeverity()).isEqualTo(AlertSeverity.SEVERE);
        assertThat(event.getRiskScore()).isEqualTo(85);
        verify(alertEventRepo).saveAndFlush(any());
        verify(redisPubSub).publish(any());
    }

    @Test
    void triggerAlertUnknownRouteThrows() {
        when(savedRouteRepo.findById(ROUTE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.triggerAlert(ROUTE_ID, 50, AlertSeverity.MODERATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown saved route");
    }

    @Test
    void acknowledgeAlertTransitionsState() {
        UUID eventId = UUID.randomUUID();
        AlertEvent event = new AlertEvent(ROUTE_ID, TENANT_ID, AlertSeverity.SEVERE, 80);
        ReflectionTestUtils.setField(event, "id", eventId);
        when(alertEventRepo.findById(eventId)).thenReturn(Optional.of(event));
        when(alertEventRepo.transitionState(eventId, "ACKNOWLEDGED")).thenReturn(true);
        when(alertEscalationRepo.findByAlertEventIdAndTargetMemberIdAndRespondedAtIsNull(eventId, MEMBER_ID))
                .thenReturn(List.of());

        AlertEvent result = service.acknowledgeAlert(eventId, MEMBER_ID);

        assertThat(result).isNotNull();
        verify(redisPubSub).publish(any());
    }

    @Test
    void escalateAlertCreatesEscalationChain() {
        UUID eventId = UUID.randomUUID();
        AlertEvent event = new AlertEvent(ROUTE_ID, TENANT_ID, AlertSeverity.SEVERE, 90);
        ReflectionTestUtils.setField(event, "id", eventId);
        event.setState(AlertState.NOTIFIED);
        when(alertEventRepo.findById(eventId)).thenReturn(Optional.of(event));
        when(alertEventRepo.transitionState(eq(eventId), anyString())).thenReturn(true);

        TenantMember admin = tenantMember();
        when(tenantMemberRepo.findByTenantIdAndRoleAndDeletedAtIsNull(TENANT_ID, MemberRole.MEMBER))
                .thenReturn(List.of());
        when(tenantMemberRepo.findByTenantIdAndRoleAndDeletedAtIsNull(TENANT_ID, MemberRole.ADMIN))
                .thenReturn(List.of(admin));
        when(tenantMemberRepo.findByTenantIdAndRoleAndDeletedAtIsNull(TENANT_ID, MemberRole.OWNER))
                .thenReturn(List.of());

        List<AlertEscalation> chain = service.escalateAlert(eventId);

        assertThat(chain).hasSize(1);
        verify(alertEscalationRepo).saveAll(any());
        verify(redisPubSub).publish(any());
    }

    @Test
    void resolveAlertTransitionsToResolved() {
        UUID eventId = UUID.randomUUID();
        AlertEvent event = new AlertEvent(ROUTE_ID, TENANT_ID, AlertSeverity.MODERATE, 40);
        ReflectionTestUtils.setField(event, "id", eventId);
        event.setState(AlertState.NOTIFIED);
        when(alertEventRepo.findById(eventId)).thenReturn(Optional.of(event));
        when(alertEventRepo.transitionState(eventId, "RESOLVED")).thenReturn(true);

        AlertEvent result = service.resolveAlert(eventId);

        assertThat(result).isNotNull();
        verify(redisPubSub).publish(any());
    }

    @Test
    void resolveAlreadyResolvedAlertIsNoOp() {
        UUID eventId = UUID.randomUUID();
        AlertEvent event = new AlertEvent(ROUTE_ID, TENANT_ID, AlertSeverity.MODERATE, 40);
        ReflectionTestUtils.setField(event, "id", eventId);
        event.setState(AlertState.RESOLVED);
        when(alertEventRepo.findById(eventId)).thenReturn(Optional.of(event));

        AlertEvent result = service.resolveAlert(eventId);

        assertThat(result).isNotNull();
    }

    @Test
    void handleTriggeredEventTransitionsToNotified() {
        UUID eventId = UUID.randomUUID();
        AlertEvent event = new AlertEvent(ROUTE_ID, TENANT_ID, AlertSeverity.SEVERE, 70);
        ReflectionTestUtils.setField(event, "id", eventId);
        event.setState(AlertState.TRIGGERED);
        when(alertEventRepo.findById(eventId)).thenReturn(Optional.of(event));
        when(alertEventRepo.transitionState(eventId, "NOTIFIED")).thenReturn(true);

        service.handleTriggeredEvent(eventId);

        verify(redisPubSub).publish(any());
    }

    @Test
    void handleTriggeredEventSkipsNonTriggeredState() {
        UUID eventId = UUID.randomUUID();
        AlertEvent event = new AlertEvent(ROUTE_ID, TENANT_ID, AlertSeverity.SEVERE, 70);
        ReflectionTestUtils.setField(event, "id", eventId);
        event.setState(AlertState.NOTIFIED);
        when(alertEventRepo.findById(eventId)).thenReturn(Optional.of(event));

        service.handleTriggeredEvent(eventId);

        // Should not attempt transition or publish
    }

    @Test
    void unknownAlertEventThrows() {
        UUID eventId = UUID.randomUUID();
        when(alertEventRepo.findById(eventId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acknowledgeAlert(eventId, MEMBER_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown alert event");
    }
}
