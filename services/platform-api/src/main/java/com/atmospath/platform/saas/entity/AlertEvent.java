package com.atmospath.platform.saas.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Lifecycle of one risk alert on a saved route. State changes are validated
 * by the {@code transition_alert_state()} database function, which raises an
 * exception on illegal transitions.
 */
@Entity
@Table(name = "alert_event")
public class AlertEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "saved_route_id")
    private UUID savedRouteId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private AlertState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private AlertSeverity severity;

    @Column(name = "risk_score")
    private Integer riskScore;

    @Column(name = "triggered_at", nullable = false, updatable = false)
    private Instant triggeredAt;

    @Column(name = "notified_at")
    private Instant notifiedAt;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "triggered_by", nullable = false)
    private String triggeredBy = "SYSTEM";

    protected AlertEvent() {
    }

    public AlertEvent(UUID savedRouteId, UUID tenantId, AlertSeverity severity, int riskScore) {
        this.savedRouteId = savedRouteId;
        this.tenantId = tenantId;
        this.severity = severity;
        this.riskScore = riskScore;
        this.state = AlertState.TRIGGERED;
        this.triggeredAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (triggeredAt == null) {
            triggeredAt = Instant.now();
        }
        if (triggeredBy == null) {
            triggeredBy = "SYSTEM";
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getSavedRouteId() {
        return savedRouteId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public AlertState getState() {
        return state;
    }

    public void setState(AlertState state) {
        this.state = state;
    }

    public AlertSeverity getSeverity() {
        return severity;
    }

    public Integer getRiskScore() {
        return riskScore;
    }

    public void setRiskScore(Integer riskScore) {
        this.riskScore = riskScore;
    }

    public Instant getTriggeredAt() {
        return triggeredAt;
    }

    public Instant getNotifiedAt() {
        return notifiedAt;
    }

    public void setNotifiedAt(Instant notifiedAt) {
        this.notifiedAt = notifiedAt;
    }

    public Instant getEscalatedAt() {
        return escalatedAt;
    }

    public void setEscalatedAt(Instant escalatedAt) {
        this.escalatedAt = escalatedAt;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public void setAcknowledgedAt(Instant acknowledgedAt) {
        this.acknowledgedAt = acknowledgedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public void setTriggeredBy(String triggeredBy) {
        this.triggeredBy = triggeredBy;
    }
}
