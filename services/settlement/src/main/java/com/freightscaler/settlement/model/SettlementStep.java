package com.freightscaler.settlement.model;

/**
 * The five sequential steps of the settlement saga.
 * Each step must complete before the next begins; on failure,
 * compensating transactions roll back all previously completed steps.
 */
public enum SettlementStep {
    DELIVERY_CONFIRMED(1, "Delivery Confirmed"),
    INSPECTION(2, "Inspection"),
    AMOUNT_DETERMINED(3, "Amount Determined"),
    PAYMENT_RELEASED(4, "Payment Released"),
    INVOICE_ISSUED(5, "Invoice Issued");

    private final int stepNumber;
    private final String displayName;

    SettlementStep(int stepNumber, String displayName) {
        this.stepNumber = stepNumber;
        this.displayName = displayName;
    }

    public int getStepNumber() {
        return stepNumber;
    }

    public String getDisplayName() {
        return displayName;
    }
}
