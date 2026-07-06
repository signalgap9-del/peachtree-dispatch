package com.atmospath.platform.account;

public class QuotaExceededException extends RuntimeException {
    private final MeteredFeature feature;
    private final PlanCode plan;
    private final int used;
    private final int limit;
    private final String resetsAt;

    public QuotaExceededException(MeteredFeature feature, PlanCode plan, int used, int limit, String resetsAt) {
        super(feature.label() + " quota exceeded for " + plan);
        this.feature = feature;
        this.plan = plan;
        this.used = used;
        this.limit = limit;
        this.resetsAt = resetsAt;
    }

    public MeteredFeature feature() {
        return feature;
    }

    public PlanCode plan() {
        return plan;
    }

    public int used() {
        return used;
    }

    public int limit() {
        return limit;
    }

    public String resetsAt() {
        return resetsAt;
    }
}
