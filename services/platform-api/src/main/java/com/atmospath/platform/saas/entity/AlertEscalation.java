package com.atmospath.platform.saas.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * One rung of an alert escalation chain: level 1 targets a MEMBER, level 2
 * an ADMIN, level 3 the OWNER.
 */
@Entity
@Table(name = "alert_escalation")
public class AlertEscalation {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "alert_event_id", nullable = false, updatable = false)
    private UUID alertEventId;

    @Column(name = "level", nullable = false, updatable = false)
    private int level;

    @Column(name = "target_member_id", updatable = false)
    private UUID targetMemberId;

    @Column(name = "notified_at")
    private Instant notifiedAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    protected AlertEscalation() {
    }

    public AlertEscalation(UUID alertEventId, int level, UUID targetMemberId) {
        this.alertEventId = alertEventId;
        this.level = level;
        this.targetMemberId = targetMemberId;
        this.notifiedAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (notifiedAt == null) {
            notifiedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getAlertEventId() {
        return alertEventId;
    }

    public int getLevel() {
        return level;
    }

    public UUID getTargetMemberId() {
        return targetMemberId;
    }

    public Instant getNotifiedAt() {
        return notifiedAt;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public void setRespondedAt(Instant respondedAt) {
        this.respondedAt = respondedAt;
    }
}
