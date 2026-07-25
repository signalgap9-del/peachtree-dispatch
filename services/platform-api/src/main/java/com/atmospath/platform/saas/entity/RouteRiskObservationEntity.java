package com.atmospath.platform.saas.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * Point-in-time risk observation for a saved route, stored in a TimescaleDB
 * hypertable with 7-day chunks, hourly/daily continuous aggregates, and a
 * 90-day retention policy. {@code factors} carries the raw signal breakdown
 * as JSON.
 */
@Entity
@Table(name = "route_risk_observation")
@IdClass(RouteRiskObservationId.class)
public class RouteRiskObservationEntity {

    @Id
    @Column(name = "time", nullable = false, updatable = false)
    private Instant time;

    @Id
    @Column(name = "saved_route_id", nullable = false, updatable = false)
    private UUID savedRouteId;

    @Column(name = "risk_score")
    private Integer riskScore;

    @Column(name = "risk_level")
    private String riskLevel;

    @Column(name = "factors", columnDefinition = "jsonb")
    private String factors;

    protected RouteRiskObservationEntity() {
    }

    public RouteRiskObservationEntity(Instant time, UUID savedRouteId, Integer riskScore,
                                      String riskLevel, String factors) {
        this.time = time;
        this.savedRouteId = savedRouteId;
        this.riskScore = riskScore;
        this.riskLevel = riskLevel;
        this.factors = factors;
    }

    public Instant getTime() {
        return time;
    }

    public UUID getSavedRouteId() {
        return savedRouteId;
    }

    public Integer getRiskScore() {
        return riskScore;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public String getFactors() {
        return factors;
    }

    public void setFactors(String factors) {
        this.factors = factors;
    }
}
