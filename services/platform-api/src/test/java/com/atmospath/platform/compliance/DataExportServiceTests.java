package com.atmospath.platform.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.atmospath.platform.account.MeteredFeature;
import com.atmospath.platform.account.UsageRepository;
import com.atmospath.platform.compliance.dto.DataExportResponse;
import com.atmospath.platform.saas.entity.MemberRole;
import com.atmospath.platform.saas.entity.SavedPlaceEntity;
import com.atmospath.platform.saas.entity.SavedRouteEntity;
import com.atmospath.platform.saas.entity.Subscription;
import com.atmospath.platform.saas.entity.SubscriptionPlan;
import com.atmospath.platform.saas.entity.SubscriptionState;
import com.atmospath.platform.saas.entity.TenantMember;
import com.atmospath.platform.saas.repository.SavedPlaceRepository;
import com.atmospath.platform.saas.repository.SavedRouteRepository;
import com.atmospath.platform.saas.repository.SubscriptionRepository;
import com.atmospath.platform.saas.repository.TenantMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DataExportServiceTests {

    private static final String SUBJECT = "google-oauth2|user123";
    private static final String EMAIL = "user@example.com";
    private static final UUID TENANT_ID = UUID.nameUUIDFromBytes(
            ("tenant:" + SUBJECT).getBytes(StandardCharsets.UTF_8));
    private static final UUID MEMBER_ID = UUID.randomUUID();

    private TenantMemberRepository memberRepository;
    private SavedRouteRepository savedRouteRepository;
    private SavedPlaceRepository savedPlaceRepository;
    private SubscriptionRepository subscriptionRepository;
    private UsageRepository usageRepository;
    private DataExportService service;

    @BeforeEach
    void setUp() {
        memberRepository = mock(TenantMemberRepository.class);
        savedRouteRepository = mock(SavedRouteRepository.class);
        savedPlaceRepository = mock(SavedPlaceRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        usageRepository = mock(UsageRepository.class);
        service = new DataExportService(memberRepository, savedRouteRepository,
                savedPlaceRepository, subscriptionRepository, usageRepository);
    }

    @Test
    void exportReturnsTheUsersData() {
        TenantMember member = new TenantMember(TENANT_ID, EMAIL, "Test User", MemberRole.OWNER);
        ReflectionTestUtils.setField(member, "id", MEMBER_ID);
        when(memberRepository.findByTenantIdAndEmailAndDeletedAtIsNull(TENANT_ID, EMAIL))
                .thenReturn(Optional.of(member));

        SavedRouteEntity route = new SavedRouteEntity(MEMBER_ID, null, "Morning delivery loop");
        route.setOriginName("Atlanta hub");
        route.setDestinationName("Decatur");
        when(savedRouteRepository.findByMemberIdAndDeletedAtIsNull(MEMBER_ID))
                .thenReturn(List.of(route));

        SavedPlaceEntity place = new SavedPlaceEntity(MEMBER_ID, null, "Home", 33.749, -84.388);
        when(savedPlaceRepository.findByMemberIdAndDeletedAtIsNull(MEMBER_ID))
                .thenReturn(List.of(place));

        Subscription subscription = new Subscription(
                TENANT_ID, SubscriptionPlan.PRO, SubscriptionState.ACTIVE, null, null);
        subscription.setLemonsqueezyCustomerId("ls_cust_1");
        when(subscriptionRepository.findByTenantIdOrderByValidToDesc(TENANT_ID))
                .thenReturn(List.of(subscription));

        when(usageRepository.current(eq(TENANT_ID), any(MeteredFeature.class), any(LocalDate.class)))
                .thenReturn(0);
        when(usageRepository.current(eq(TENANT_ID), eq(MeteredFeature.ROUTE_PLAN), any(LocalDate.class)))
                .thenReturn(7);

        DataExportResponse export = service.export(TENANT_ID, SUBJECT, EMAIL);

        assertThat(export.subject()).isEqualTo(SUBJECT);
        assertThat(export.profile()).isNotNull();
        assertThat(export.profile().email()).isEqualTo(EMAIL);
        assertThat(export.profile().displayName()).isEqualTo("Test User");
        assertThat(export.profile().role()).isEqualTo("OWNER");
        assertThat(export.savedRoutes()).hasSize(1);
        assertThat(export.savedRoutes().get(0).name()).isEqualTo("Morning delivery loop");
        assertThat(export.savedRoutes().get(0).originName()).isEqualTo("Atlanta hub");
        assertThat(export.savedPlaces()).hasSize(1);
        assertThat(export.savedPlaces().get(0).name()).isEqualTo("Home");
        assertThat(export.savedPlaces().get(0).latitude()).isEqualTo(33.749);
        assertThat(export.subscriptions()).hasSize(1);
        assertThat(export.subscriptions().get(0).plan()).isEqualTo("PRO");
        assertThat(export.subscriptions().get(0).lemonsqueezyCustomerId()).isEqualTo("ls_cust_1");
        assertThat(export.usageSummary())
                .containsEntry("ROUTE_PLAN", 7)
                .containsEntry("PLACE_SEARCH", 0);
        assertThat(export.exportedAt()).isNotNull();
    }

    @Test
    void exportWithoutMemberRowUsesDerivedIdentity() {
        when(memberRepository.findByTenantIdAndEmailAndDeletedAtIsNull(TENANT_ID, EMAIL))
                .thenReturn(Optional.empty());
        UUID derivedMemberId = UUID.nameUUIDFromBytes(
                ("user:" + SUBJECT).getBytes(StandardCharsets.UTF_8));
        when(savedRouteRepository.findByMemberIdAndDeletedAtIsNull(derivedMemberId))
                .thenReturn(List.of());
        when(savedPlaceRepository.findByMemberIdAndDeletedAtIsNull(derivedMemberId))
                .thenReturn(List.of());
        when(subscriptionRepository.findByTenantIdOrderByValidToDesc(TENANT_ID))
                .thenReturn(List.of());

        DataExportResponse export = service.export(TENANT_ID, SUBJECT, EMAIL);

        assertThat(export.profile()).isNull();
        assertThat(export.savedRoutes()).isEmpty();
        verify(savedRouteRepository).findByMemberIdAndDeletedAtIsNull(derivedMemberId);
    }
}
