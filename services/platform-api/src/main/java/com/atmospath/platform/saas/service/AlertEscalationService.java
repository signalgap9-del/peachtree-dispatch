package com.atmospath.platform.saas.service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the alert state machine. Every transition is validated by the
 * {@code transition_alert_state()} database function, which raises an
 * exception on illegal paths (for example RESOLVED -> ESCALATED); this
 * service translates those failures into {@link IllegalStateException}.
 */
@Service
@ConditionalOnProperty(name = "atmospath.saas.enabled", havingValue = "true")
public class AlertEscalationService {

    private static final Logger log = LoggerFactory.getLogger(AlertEscalationService.class);
    private static final List<MemberRole> ESCALATION_CHAIN =
            List.of(MemberRole.MEMBER, MemberRole.ADMIN, MemberRole.OWNER);

    private final AlertEventRepository alertEventRepository;
    private final AlertEscalationRepository alertEscalationRepository;
    private final SavedRouteRepository savedRouteRepository;
    private final TenantMemberRepository tenantMemberRepository;
    private final RedisAlertPubSub redisAlertPubSub;
    private final EntityManager entityManager;

    public AlertEscalationService(AlertEventRepository alertEventRepository,
                                  AlertEscalationRepository alertEscalationRepository,
                                  SavedRouteRepository savedRouteRepository,
                                  TenantMemberRepository tenantMemberRepository,
                                  RedisAlertPubSub redisAlertPubSub,
                                  EntityManager entityManager) {
        this.alertEventRepository = alertEventRepository;
        this.alertEscalationRepository = alertEscalationRepository;
        this.savedRouteRepository = savedRouteRepository;
        this.tenantMemberRepository = tenantMemberRepository;
        this.redisAlertPubSub = redisAlertPubSub;
        this.entityManager = entityManager;
    }

    /**
     * Records a new risk alert and immediately moves it to NOTIFIED so
     * downstream fan-out (SSE, notifications) sees one consistent event.
     */
    @Transactional("saasTransactionManager")
    public AlertEvent triggerAlert(UUID savedRouteId, int riskScore, AlertSeverity severity) {
        SavedRouteEntity route = savedRouteRepository.findById(savedRouteId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown saved route: " + savedRouteId));
        UUID tenantId = tenantMemberRepository.findById(route.getMemberId())
                .map(TenantMember::getTenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "Saved route " + savedRouteId + " has no resolvable tenant member"));
        AlertEvent event = new AlertEvent(savedRouteId, tenantId, severity, riskScore);
        alertEventRepository.saveAndFlush(event);
        requireTransition(event, AlertState.NOTIFIED);
        log.info("Alert {} triggered for route {} (risk={}, severity={})",
                event.getId(), savedRouteId, riskScore, severity);
        redisAlertPubSub.publish(event);
        return event;
    }

    @Transactional("saasTransactionManager")
    public AlertEvent acknowledgeAlert(UUID eventId, UUID memberId) {
        AlertEvent event = loadEvent(eventId);
        requireTransition(event, AlertState.ACKNOWLEDGED);
        Instant respondedAt = Instant.now();
        alertEscalationRepository
                .findByAlertEventIdAndTargetMemberIdAndRespondedAtIsNull(eventId, memberId)
                .forEach(escalation -> escalation.setRespondedAt(respondedAt));
        log.info("Alert {} acknowledged by member {}", eventId, memberId);
        redisAlertPubSub.publish(event);
        return event;
    }

    /**
     * Fans the alert out up the role chain: level 1 MEMBER, level 2 ADMIN,
     * level 3 OWNER. Tiers without a member are skipped.
     */
    @Transactional("saasTransactionManager")
    public List<AlertEscalation> escalateAlert(UUID eventId) {
        AlertEvent event = loadEvent(eventId);
        if (event.getState() == AlertState.TRIGGERED) {
            requireTransition(event, AlertState.NOTIFIED);
        }
        requireTransition(event, AlertState.ESCALATED);

        List<AlertEscalation> chain = ESCALATION_CHAIN.stream()
                .map(role -> firstMemberInRole(event.getTenantId(), role))
                .filter(Objects::nonNull)
                .map(target -> new AlertEscalation(eventId, chainLevel(target.getRole()), target.getId()))
                .toList();
        if (chain.isEmpty()) {
            log.warn("No escalation targets found for tenant of alert {}", eventId);
        }
        alertEscalationRepository.saveAll(chain);
        log.info("Alert {} escalated to {} target(s)", eventId, chain.size());
        redisAlertPubSub.publish(event);
        return chain;
    }

    /** Auto-resolve path used when the observed risk drops below threshold. */
    @Transactional("saasTransactionManager")
    public AlertEvent resolveAlert(UUID eventId) {
        AlertEvent event = loadEvent(eventId);
        if (event.getState() == AlertState.RESOLVED) {
            return event;
        }
        requireTransition(event, AlertState.RESOLVED);
        log.info("Alert {} resolved", eventId);
        redisAlertPubSub.publish(event);
        return event;
    }

    /**
     * CDC entry point: the row already exists (replicated INSERT), so this
     * only runs the notification transition and fan-out.
     */
    @Transactional("saasTransactionManager")
    public void handleTriggeredEvent(UUID eventId) {
        AlertEvent event = loadEvent(eventId);
        if (event.getState() != AlertState.TRIGGERED) {
            return;
        }
        requireTransition(event, AlertState.NOTIFIED);
        redisAlertPubSub.publish(event);
    }

    private AlertEvent loadEvent(UUID eventId) {
        return alertEventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown alert event: " + eventId));
    }

    /**
     * Applies the transition through the database function and refreshes
     * the managed entity so the persistence context matches the row the
     * function just updated. The function raises an exception on illegal
     * transitions; that becomes an {@link IllegalStateException} here.
     */
    private void requireTransition(AlertEvent event, AlertState to) {
        try {
            alertEventRepository.transitionState(event.getId(), to.name());
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "Illegal alert transition " + event.getState() + " -> " + to
                            + " for event " + event.getId(), ex);
        }
        entityManager.refresh(event);
    }

    private TenantMember firstMemberInRole(UUID tenantId, MemberRole role) {
        return tenantMemberRepository.findByTenantIdAndRoleAndDeletedAtIsNull(tenantId, role)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private static int chainLevel(MemberRole role) {
        return ESCALATION_CHAIN.indexOf(role) + 1;
    }
}
