package com.atmospath.platform.account;

public enum MeteredFeature {
    ROUTE_PLAN("Route plans"),
    PLACE_SEARCH("Place searches"),
    LOCATION_RISK("Location risk checks"),
    ALERT_SEARCH("Alert searches"),
    SAVED_ROUTE("Saved routes"),
    SAVED_PLACE("Saved places");

    private final String label;

    MeteredFeature(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
