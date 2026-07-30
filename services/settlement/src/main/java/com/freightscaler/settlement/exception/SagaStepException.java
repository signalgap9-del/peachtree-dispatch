package com.freightscaler.settlement.exception;

import com.freightscaler.settlement.model.SettlementStep;

/**
 * Thrown when a saga step fails. Carries the failed step and a detail message
 * so the orchestrator can run compensating transactions for completed steps
 * and decide between FAILED and DISPUTED terminal states.
 */
public class SagaStepException extends RuntimeException {

    private final SettlementStep failedStep;
    private final String detail;
    private final boolean dispute;

    public SagaStepException(SettlementStep failedStep, String detail) {
        this(failedStep, detail, false, null);
    }

    public SagaStepException(SettlementStep failedStep, String detail, boolean dispute) {
        this(failedStep, detail, dispute, null);
    }

    public SagaStepException(SettlementStep failedStep, String detail, boolean dispute, Throwable cause) {
        super("Saga step %d (%s) failed: %s".formatted(
                failedStep.getStepNumber(), failedStep.getDisplayName(), detail), cause);
        this.failedStep = failedStep;
        this.detail = detail;
        this.dispute = dispute;
    }

    public SettlementStep getFailedStep() {
        return failedStep;
    }

    public String getDetail() {
        return detail;
    }

    /**
     * @return true if the failure should result in DISPUTED status (e.g. cargo damage),
     *         false for a plain FAILED status (e.g. insufficient funds)
     */
    public boolean isDispute() {
        return dispute;
    }
}
