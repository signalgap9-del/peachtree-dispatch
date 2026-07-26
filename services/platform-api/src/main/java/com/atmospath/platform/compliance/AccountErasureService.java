package com.atmospath.platform.compliance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.atmospath.platform.saas.entity.Subscription;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GDPR Article 17 right-to-erasure for one member. Executes the member
 * erasure procedure from docs/data-retention.md section 3.1 against the
 * JPA model, children first, in a single transaction:
 *
 * <ol>
 *   <li>risk observations (no FK cascade, delete explicitly),</li>
 *   <li>alert events by route (escalations cascade via FK),</li>
 *   <li>API keys (member FK is ON DELETE SET NULL, delete explicitly),</li>
 *   <li>workspace memberships,</li>
 *   <li>audit log anonymized, not deleted (legitimate-interest trail),</li>
 *   <li>saved routes and places (hard delete, including soft-deleted),</li>
 *   <li>subscriptions cancelled but retained as billing evidence,</li>
 *   <li>the member row itself.</li>
 * </ol>
 *
 * <p>Idempotent: when no member row exists (already erased, or never
 * created) the call is a no-op returning {@code false}.
 */
@Service
@ConditionalOnProperty(name = "atmospath.saas.enabled", havingValue = "true")
public class AccountErasureService {

    private static final Logger log = LoggerFactory.getLogger(AccountErasureService.class);

    private final TenantMemberRepository memberRepository;
    private final SavedRouteRepository savedRouteRepository;
    private final SavedPlaceRepository savedPlaceRepository;
    private final RouteRiskObservationRepository observationRepository;
    private final AlertEventRepository alertEventRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final AuditLogRepository auditLogRepository;
    private final SubscriptionRepository subscriptionRepository;

    public AccountErasureService(TenantMemberRepository memberRepository,
                                 SavedRouteRepository savedRouteRepository,
                                 SavedPlaceRepository savedPlaceRepository,
                                 RouteRiskObservationRepository observationRepository,
                                 AlertEventRepository alertEventRepository,
                                 ApiKeyRepository apiKeyRepository,
                                 WorkspaceMemberRepository workspaceMemberRepository,
                                 AuditLogRepository auditLogRepository,
                                 SubscriptionRepository subscriptionRepository) {
        this.memberRepository = memberRepository;
        this.savedRouteRepository = savedRouteRepository;
        this.savedPlaceRepository = savedPlaceRepository;
        this.observationRepository = observationRepository;
        this.alertEventRepository = alertEventRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.auditLogRepository = auditLogRepository;
        this.subscriptionRepository = subscriptionRepository;
    }

    @Transactional(transactionManager = "saasTransactionManager")
    public boolean erase(UUID tenantId, String email) {
        var maybeMember = memberRepository.findByTenantIdAndEmail(tenantId, email);
        if (maybeMember.isEmpty()) {
            log.info("GDPR erasure no-op: no member for tenant={} (already erased)", tenantId);
            return false;
        }
        TenantMember member = maybeMember.get();
        UUID memberId = member.getId();

        List<UUID> routeIds = savedRouteRepository.findByMemberId(memberId).stream()
                .map(route -> route.getId())
                .toList();
        if (!routeIds.isEmpty()) {
            observationRepository.deleteBySavedRouteIdIn(routeIds);
            alertEventRepository.deleteBySavedRouteIdIn(routeIds);
        }
        apiKeyRepository.deleteByMemberId(memberId);
        workspaceMemberRepository.deleteByMemberId(memberId);
        int anonymizedAuditRows = auditLogRepository.anonymizeByMemberId(memberId);
        savedRouteRepository.deleteByMemberId(memberId);
        savedPlaceRepository.deleteByMemberId(memberId);
        cancelSubscriptions(tenantId);
        memberRepository.delete(member);

        // Compliance trail: who/when/scope only, never the erased content.
        log.info("GDPR erasure completed: tenant={} member={} routes={} auditRowsAnonymized={}",
                tenantId, memberId, routeIds.size(), anonymizedAuditRows);
        return true;
    }

    /**
     * Retention policy: subscription rows are billing evidence kept for
     * 7 years, so erasure cancels them rather than deleting them. Lemon
     * Squeezy remains the authoritative merchant of record.
     */
    private void cancelSubscriptions(UUID tenantId) {
        for (Subscription subscription : subscriptionRepository.findActiveByTenantId(tenantId, Instant.now())) {
            subscription.setStatus(SubscriptionState.CANCELLED);
            subscription.setCancelAtPeriodEnd(true);
            subscriptionRepository.save(subscription);
        }
    }
}
