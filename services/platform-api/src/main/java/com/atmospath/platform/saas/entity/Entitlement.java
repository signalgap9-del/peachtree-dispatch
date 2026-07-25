package com.atmospath.platform.saas.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Per-feature allowance attached to a subscription. {@code quota_limit = 0}
 * means unlimited daily usage; the saved-route/place limits are capacity
 * ceilings rather than daily counters.
 */
@Entity
@Table(name = "entitlement")
public class Entitlement {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Column(name = "feature", nullable = false)
    private String feature;

    @Column(name = "quota_limit", nullable = false)
    private int quotaLimit;

    @Column(name = "saved_route_limit", nullable = false)
    private int savedRouteLimit;

    @Column(name = "saved_place_limit", nullable = false)
    private int savedPlaceLimit;

    protected Entitlement() {
    }

    public Entitlement(UUID subscriptionId, String feature, int quotaLimit,
                       int savedRouteLimit, int savedPlaceLimit) {
        this.subscriptionId = subscriptionId;
        this.feature = feature;
        this.quotaLimit = quotaLimit;
        this.savedRouteLimit = savedRouteLimit;
        this.savedPlaceLimit = savedPlaceLimit;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getSubscriptionId() {
        return subscriptionId;
    }

    public String getFeature() {
        return feature;
    }

    public int getQuotaLimit() {
        return quotaLimit;
    }

    public void setQuotaLimit(int quotaLimit) {
        this.quotaLimit = quotaLimit;
    }

    public int getSavedRouteLimit() {
        return savedRouteLimit;
    }

    public void setSavedRouteLimit(int savedRouteLimit) {
        this.savedRouteLimit = savedRouteLimit;
    }

    public int getSavedPlaceLimit() {
        return savedPlaceLimit;
    }

    public void setSavedPlaceLimit(int savedPlaceLimit) {
        this.savedPlaceLimit = savedPlaceLimit;
    }
}
