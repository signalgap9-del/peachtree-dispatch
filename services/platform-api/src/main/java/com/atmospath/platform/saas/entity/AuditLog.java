package com.atmospath.platform.saas.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Append-only audit trail. Rows are written either by the database audit
 * triggers (which read {@code app.tenant_id}/{@code app.member_id} session
 * settings) or explicitly by services for business-level events.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "tenant_id", updatable = false)
    private UUID tenantId;

    @Column(name = "member_id", updatable = false)
    private UUID memberId;

    @Column(name = "action", nullable = false, updatable = false)
    private String action;

    @Column(name = "resource_type", nullable = false, updatable = false)
    private String resourceType;

    @Column(name = "resource_id", updatable = false)
    private UUID resourceId;

    @Column(name = "changes", columnDefinition = "jsonb", updatable = false)
    private String changes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLog() {
    }

    public AuditLog(UUID tenantId, UUID memberId, String action,
                    String resourceType, UUID resourceId, String changes) {
        this.tenantId = tenantId;
        this.memberId = memberId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.changes = changes;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getMemberId() {
        return memberId;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public String getChanges() {
        return changes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
