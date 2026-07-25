package com.atmospath.platform.saas.entity;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Daily per-tenant feature usage. The table is range-partitioned by month
 * over {@code usage_date} with an auto-partition trigger, so inserts never
 * fail for future months.
 */
@Entity
@Table(name = "usage_record")
@IdClass(UsageRecordId.class)
public class UsageRecord {

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Id
    @Column(name = "feature", nullable = false, updatable = false)
    private String feature;

    @Id
    @Column(name = "usage_date", nullable = false, updatable = false)
    private LocalDate usageDate;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "count", nullable = false)
    private int count;

    protected UsageRecord() {
    }

    public UsageRecord(UUID tenantId, String feature, LocalDate usageDate, int count) {
        this.tenantId = tenantId;
        this.feature = feature;
        this.usageDate = usageDate;
        this.count = count;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getFeature() {
        return feature;
    }

    public LocalDate getUsageDate() {
        return usageDate;
    }

    public UUID getId() {
        return id;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }
}
