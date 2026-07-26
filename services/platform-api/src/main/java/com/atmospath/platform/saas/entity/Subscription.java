package com.atmospath.platform.saas.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Temporal subscription row. The schema keeps at most one open row per
 * tenant: open rows carry {@code valid_to = 'infinity'} (represented here as
 * {@link #VALID_FOREVER}); proration closes the old row and inserts a new
 * version with {@code version + 1}.
 */
@Entity
@Table(name = "subscription")
public class Subscription {

    /** Java representation of the Postgres {@code 'infinity'} valid_to sentinel. */
    public static final Instant VALID_FOREVER = Instant.MAX;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan", nullable = false)
    private SubscriptionPlan plan;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SubscriptionState status;

    @Column(name = "current_period_start")
    private LocalDate currentPeriodStart;

    @Column(name = "current_period_end")
    private LocalDate currentPeriodEnd;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to", nullable = false)
    private Instant validTo;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "lemonsqueezy_subscription_id")
    private String lemonsqueezySubscriptionId;

    @Column(name = "lemonsqueezy_customer_id")
    private String lemonsqueezyCustomerId;

    @Column(name = "lemonsqueezy_variant_id")
    private String lemonsqueezyVariantId;

    @Column(name = "billing_status")
    private String billingStatus;

    @Column(name = "billing_period_end")
    private Instant billingPeriodEnd;

    @Column(name = "cancel_at_period_end")
    private Boolean cancelAtPeriodEnd;

    protected Subscription() {
    }

    public Subscription(UUID tenantId, SubscriptionPlan plan, SubscriptionState status,
                        LocalDate currentPeriodStart, LocalDate currentPeriodEnd) {
        this.tenantId = tenantId;
        this.plan = plan;
        this.status = status;
        this.currentPeriodStart = currentPeriodStart;
        this.currentPeriodEnd = currentPeriodEnd;
        this.validFrom = Instant.now();
        this.validTo = VALID_FOREVER;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (validFrom == null) {
            validFrom = Instant.now();
        }
        if (validTo == null) {
            validTo = VALID_FOREVER;
        }
    }

    public boolean isOpen() {
        return validTo == null || VALID_FOREVER.equals(validTo);
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public SubscriptionPlan getPlan() {
        return plan;
    }

    public void setPlan(SubscriptionPlan plan) {
        this.plan = plan;
    }

    public SubscriptionState getStatus() {
        return status;
    }

    public void setStatus(SubscriptionState status) {
        this.status = status;
    }

    public LocalDate getCurrentPeriodStart() {
        return currentPeriodStart;
    }

    public void setCurrentPeriodStart(LocalDate currentPeriodStart) {
        this.currentPeriodStart = currentPeriodStart;
    }

    public LocalDate getCurrentPeriodEnd() {
        return currentPeriodEnd;
    }

    public void setCurrentPeriodEnd(LocalDate currentPeriodEnd) {
        this.currentPeriodEnd = currentPeriodEnd;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(Instant validFrom) {
        this.validFrom = validFrom;
    }

    public Instant getValidTo() {
        return validTo;
    }

    public void setValidTo(Instant validTo) {
        this.validTo = validTo;
    }

    public Integer getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getLemonsqueezySubscriptionId() {
        return lemonsqueezySubscriptionId;
    }

    public void setLemonsqueezySubscriptionId(String lemonsqueezySubscriptionId) {
        this.lemonsqueezySubscriptionId = lemonsqueezySubscriptionId;
    }

    public String getLemonsqueezyCustomerId() {
        return lemonsqueezyCustomerId;
    }

    public void setLemonsqueezyCustomerId(String lemonsqueezyCustomerId) {
        this.lemonsqueezyCustomerId = lemonsqueezyCustomerId;
    }

    public String getLemonsqueezyVariantId() {
        return lemonsqueezyVariantId;
    }

    public void setLemonsqueezyVariantId(String lemonsqueezyVariantId) {
        this.lemonsqueezyVariantId = lemonsqueezyVariantId;
    }

    public String getBillingStatus() {
        return billingStatus;
    }

    public void setBillingStatus(String billingStatus) {
        this.billingStatus = billingStatus;
    }

    public Instant getBillingPeriodEnd() {
        return billingPeriodEnd;
    }

    public void setBillingPeriodEnd(Instant billingPeriodEnd) {
        this.billingPeriodEnd = billingPeriodEnd;
    }

    public Boolean getCancelAtPeriodEnd() {
        return cancelAtPeriodEnd;
    }

    public void setCancelAtPeriodEnd(Boolean cancelAtPeriodEnd) {
        this.cancelAtPeriodEnd = cancelAtPeriodEnd;
    }
}
