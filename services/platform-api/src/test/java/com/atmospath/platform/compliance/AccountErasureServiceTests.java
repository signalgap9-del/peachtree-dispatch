package com.atmospath.platform.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.atmospath.platform.saas.entity.MemberRole;
import com.atmospath.platform.saas.entity.SavedRouteEntity;
import com.atmospath.platform.saas.entity.Subscription;
import com.atmospath.platform.saas.entity.SubscriptionPlan;
import com.atmospath.platform.saas.entity.SubscriptionState;
import com.atmospath.platform.saas.entity.TenantMember;
import com.atmospath.platform.saas.repository.AlertEventRepository;
import com.atmospath.platform.saas.repository.ApiKeyRepository;
import com.atmospath.platform.saas.repository.AuditLogRepository;
import com.atmospath.platform.saas.repository.RouteRiskObservationRepository;
import com.atmospath.platform.saas.repository.SavedPlaceRepository;
import com.atmospath.platform.saas.repository.SavedRouteRepository;
import com.atmospath.platform.saas.repository.SubscriptionRepository;
import com.atmospath.platform.saas.repository.TenantMemberRepository;
import com.atmospath.platform.saas.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;

class AccountErasureServiceTests {

    private static final String EMAIL = "user@example.com";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID MEMBER_ID = UUID.randomUUID();

    private TenantMemberRepository memberRepository;
    private SavedRouteRepository savedRouteRepository;
    private SavedPlaceRepository savedPlaceRepository;
    private RouteRiskObservationRepository observationRepository;
    private AlertEventRepository alertEventRepository;
    private ApiKeyRepository apiKeyRepository;
    private WorkspaceMemberRepository workspaceMemberRepository;
    private AuditLogRepository auditLogRepository;
    private SubscriptionRepository subscriptionRepository;
    private AccountErasureService service;

    @BeforeEach
    void setUp() {
        memberRepository = mock(TenantMemberRepository.class);
        savedRouteRepository = mock(SavedRouteRepository.class);
        savedPlaceRepository = mock(SavedPlaceRepository.class);
        observationRepository = mock(RouteRiskObservationRepository.class);
        alertEventRepository = mock(AlertEventRepository.class);
        apiKeyRepository = mock(ApiKeyRepository.class);
        workspaceMemberRepository = mock(WorkspaceMemberRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        service = new AccountErasureService(memberRepository, savedRouteRepository,
                savedPlaceRepository, observationRepository, alertEventRepository,
                apiKeyRepository, workspaceMemberRepository, auditLogRepository,
                subscriptionRepository);
    }

    @Test
    void eraseCascadesThroughAllMemberData() {
        TenantMember member = member();
        UUID routeOne = UUID.randomUUID();
        UUID routeTwo = UUID.randomUUID();
        when(memberRepository.findByTenantIdAndEmail(TENANT_ID, EMAIL))
                .thenReturn(Optional.of(member));
        when(savedRouteRepository.findByMemberId(MEMBER_ID))
                .thenReturn(List.of(route(routeOne), route(routeTwo)));
        when(subscriptionRepository.findActiveByTenantId(ArgumentMatchers.eq(TENANT_ID),
                ArgumentMatchers.any(Instant.class)))
                .thenReturn(List.of());

        boolean erased = service.erase(TENANT_ID, EMAIL);

        assertThat(erased).isTrue();
        InOrder order = inOrder(observationRepository, alertEventRepository, apiKeyRepository,
                workspaceMemberRepository, auditLogRepository, savedRouteRepository,
                savedPlaceRepository, memberRepository);
        order.verify(observationRepository).deleteBySavedRouteIdIn(List.of(routeOne, routeTwo));
        order.verify(alertEventRepository).deleteBySavedRouteIdIn(List.of(routeOne, routeTwo));
        order.verify(apiKeyRepository).deleteByMemberId(MEMBER_ID);
        order.verify(workspaceMemberRepository).deleteByMemberId(MEMBER_ID);
        order.verify(auditLogRepository).anonymizeByMemberId(MEMBER_ID);
        order.verify(savedRouteRepository).deleteByMemberId(MEMBER_ID);
        order.verify(savedPlaceRepository).deleteByMemberId(MEMBER_ID);
        order.verify(memberRepository).delete(member);
    }

    @Test
    void eraseIsIdempotentWhenMemberIsAlreadyGone() {
        when(memberRepository.findByTenantIdAndEmail(TENANT_ID, EMAIL))
                .thenReturn(Optional.empty());

        boolean first = service.erase(TENANT_ID, EMAIL);
        boolean second = service.erase(TENANT_ID, EMAIL);

        assertThat(first).isFalse();
        assertThat(second).isFalse();
        verifyNoInteractions(savedRouteRepository, savedPlaceRepository, observationRepository,
                alertEventRepository, apiKeyRepository, workspaceMemberRepository,
                auditLogRepository, subscriptionRepository);
    }

    @Test
    void eraseCancelsActiveSubscriptionInsteadOfDeletingIt() {
        Subscription subscription = new Subscription(
                TENANT_ID, SubscriptionPlan.PRO, SubscriptionState.ACTIVE, null, null);
        when(memberRepository.findByTenantIdAndEmail(TENANT_ID, EMAIL))
                .thenReturn(Optional.of(member()));
        when(savedRouteRepository.findByMemberId(MEMBER_ID)).thenReturn(List.of());
        when(subscriptionRepository.findActiveByTenantId(ArgumentMatchers.eq(TENANT_ID),
                ArgumentMatchers.any(Instant.class)))
                .thenReturn(List.of(subscription));

        service.erase(TENANT_ID, EMAIL);

        assertThat(subscription.getStatus()).isEqualTo(SubscriptionState.CANCELLED);
        assertThat(subscription.getCancelAtPeriodEnd()).isTrue();
        verify(subscriptionRepository).save(subscription);
        verify(subscriptionRepository, never()).delete(ArgumentMatchers.any(Subscription.class));
    }

    @Test
    void eraseWithoutRoutesSkipsRouteScopedDeletes() {
        when(memberRepository.findByTenantIdAndEmail(TENANT_ID, EMAIL))
                .thenReturn(Optional.of(member()));
        when(savedRouteRepository.findByMemberId(MEMBER_ID)).thenReturn(List.of());
        when(subscriptionRepository.findActiveByTenantId(ArgumentMatchers.eq(TENANT_ID),
                ArgumentMatchers.any(Instant.class)))
                .thenReturn(List.of());

        service.erase(TENANT_ID, EMAIL);

        verifyNoInteractions(observationRepository, alertEventRepository);
        verify(savedRouteRepository).deleteByMemberId(MEMBER_ID);
    }

    private static TenantMember member() {
        TenantMember member = new TenantMember(TENANT_ID, EMAIL, "Test User", MemberRole.OWNER);
        ReflectionTestUtils.setField(member, "id", MEMBER_ID);
        return member;
    }

    private static SavedRouteEntity route(UUID id) {
        SavedRouteEntity route = new SavedRouteEntity(MEMBER_ID, null, "route-" + id);
        ReflectionTestUtils.setField(route, "id", id);
        return route;
    }
}
