package com.atmospath.platform.saas.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Durable idempotency marker for tenant-scoped mutating operations. Keys
 * expire after 24 hours by default; the caller stores the operation result
 * out of band (e.g. response cache) keyed by the same hash.
 */
@Entity
@Table(name = "idempotency_key")
@IdClass(IdempotencyKeyId.class)
public class IdempotencyKeyRecord {

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Id
    @Column(name = "operation", nullable = false, updatable = false)
    private String operation;

    @Id
    @Column(name = "key_hash", nullable = false, updatable = false)
    private String keyHash;

    @Column(name = "resource_id", updatable = false)
    private UUID resourceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyKeyRecord() {
    }

    public IdempotencyKeyRecord(UUID tenantId, String operation, String keyHash,
                                UUID resourceId, Instant expiresAt) {
        this.tenantId = tenantId;
        this.operation = operation;
        this.keyHash = keyHash;
        this.resourceId = resourceId;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (expiresAt == null) {
            expiresAt = createdAt.plusSeconds(24 * 60 * 60);
        }
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getOperation() {
        return operation;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public void setResourceId(UUID resourceId) {
        this.resourceId = resourceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}
