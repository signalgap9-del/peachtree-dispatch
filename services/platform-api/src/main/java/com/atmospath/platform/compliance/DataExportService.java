package com.atmospath.platform.compliance;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.atmospath.platform.account.MeteredFeature;
import com.atmospath.platform.account.UsageRepository;
import com.atmospath.platform.compliance.dto.DataExportResponse;
import com.atmospath.platform.compliance.dto.ProfileExport;
import com.atmospath.platform.compliance.dto.SavedPlaceExport;
import com.atmospath.platform.compliance.dto.SavedRouteExport;
import com.atmospath.platform.compliance.dto.SubscriptionExport;
import com.atmospath.platform.saas.repository.SavedPlaceRepository;
import com.atmospath.platform.saas.repository.SavedRouteRepository;
import com.atmospath.platform.saas.repository.SubscriptionRepository;
import com.atmospath.platform.saas.repository.TenantMemberRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gathers the authenticated user's personal data for GDPR Article 15/20
 * subject access and portability requests, as inventoried in
 * docs/data-retention.md section 5.
 */
@Service
@ConditionalOnProperty(name = "atmospath.saas.enabled", havingValue = "true")
@Transactional(transactionManager = "saasTransactionManager", readOnly = true)
public class DataExportService {

    private final TenantMemberRepository memberRepository;
    private final SavedRouteRepository savedRouteRepository;
    private final SavedPlaceRepository savedPlaceRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final UsageRepository usageRepository;

    public DataExportService(TenantMemberRepository memberRepository,
                             SavedRouteRepository savedRouteRepository,
                             SavedPlaceRepository savedPlaceRepository,
                             SubscriptionRepository subscriptionRepository,
                             UsageRepository usageRepository) {
        this.memberRepository = memberRepository;
        this.savedRouteRepository = savedRouteRepository;
        this.savedPlaceRepository = savedPlaceRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.usageRepository = usageRepository;
    }

    public DataExportResponse export(UUID tenantId, String subject, String email) {
        var member = memberRepository.findByTenantIdAndEmailAndDeletedAtIsNull(tenantId, email)
                .map(row -> new ProfileExport(
                        row.getId(),
                        row.getTenantId(),
                        row.getEmail(),
                        row.getDisplayName(),
                        row.getRole() == null ? null : row.getRole().name(),
                        row.getCreatedAt()))
                .orElse(null);

        // Fall back to the deterministic identity used by TenantContextResolver
        // when no member row exists yet, so DynamoDB-backed saved items still
        // resolve to the same owner id.
        UUID memberId = member != null
                ? member.memberId()
                : UUID.nameUUIDFromBytes(("user:" + subject).getBytes(StandardCharsets.UTF_8));

        List<SavedRouteExport> routes = savedRouteRepository.findByMemberIdAndDeletedAtIsNull(memberId)
                .stream()
                .map(SavedRouteExport::from)
                .toList();
        List<SavedPlaceExport> places = savedPlaceRepository.findByMemberIdAndDeletedAtIsNull(memberId)
                .stream()
                .map(SavedPlaceExport::from)
                .toList();
        List<SubscriptionExport> subscriptions = subscriptionRepository
                .findByTenantIdOrderByValidToDesc(tenantId)
                .stream()
                .map(SubscriptionExport::from)
                .toList();

        return new DataExportResponse(
                subject,
                member,
                routes,
                places,
                subscriptions,
                usageSummary(tenantId),
                Instant.now());
    }

    private Map<String, Integer> usageSummary(UUID tenantId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Map<String, Integer> summary = new LinkedHashMap<>();
        for (MeteredFeature feature : MeteredFeature.values()) {
            summary.put(feature.name(), usageRepository.current(tenantId, feature, today));
        }
        return summary;
    }
}
