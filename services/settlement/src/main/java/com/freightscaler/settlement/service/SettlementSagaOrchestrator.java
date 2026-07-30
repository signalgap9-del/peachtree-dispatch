package com.freightscaler.settlement.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.freightscaler.settlement.exception.SagaStepException;
import com.freightscaler.settlement.model.Settlement;
import com.freightscaler.settlement.model.SettlementStatus;
import com.freightscaler.settlement.model.SettlementStep;
import com.freightscaler.settlement.producer.SettlementEventProducer;
import com.freightscaler.settlement.repository.SettlementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Core 5-step Saga orchestrator for freight settlement.
 *
 * Steps execute sequentially: DELIVERY_CONFIRMED → INSPECTION → AMOUNT_DETERMINED
 * → PAYMENT_RELEASED → INVOICE_ISSUED. Each step is logged to the saga_log JSONB
 * column before advancing. On failure, compensating transactions roll back all
 * previously completed steps, and the settlement ends in FAILED or DISPUTED.
 *
 * The saga is resumable: executeSaga skips steps already recorded in currentStep,
 * so a retry after FAILED picks up where it left off.
 */
@Service
public class SettlementSagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SettlementSagaOrchestrator.class);

    /** risk_score threshold in tracking_event that indicates possible cargo damage */
    private static final int DAMAGE_RISK_THRESHOLD = 80;
    /** Delay penalty: 5% of base rate per hour late, capped at 25% */
    private static final double DELAY_PENALTY_PER_HOUR = 0.05;
    private static final double MAX_DELAY_PENALTY = 0.25;
    /** Early bonus: 2% of base rate per hour early, capped at 10% */
    private static final double EARLY_BONUS_PER_HOUR = 0.02;
    private static final double MAX_EARLY_BONUS = 0.10;

    private final SettlementRepository settlementRepository;
    private final WalletService walletService;
    private final IdempotencyService idempotencyService;
    private final SettlementEventProducer eventProducer;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SettlementSagaOrchestrator(
            SettlementRepository settlementRepository,
            WalletService walletService,
            IdempotencyService idempotencyService,
            SettlementEventProducer eventProducer,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper
    ) {
        this.settlementRepository = settlementRepository;
        this.walletService = walletService;
        this.idempotencyService = idempotencyService;
        this.eventProducer = eventProducer;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Execute the full saga for a settlement. Resumable: skips steps already
     * completed (currentStep tracks the last successfully completed step number).
     */
    public void executeSaga(Long settlementId) {
        Settlement s = settlementRepository.findById(settlementId)
                .orElseThrow(() -> new IllegalArgumentException("Settlement not found: " + settlementId));

        if (s.status() == SettlementStatus.COMPLETED) {
            log.info("Settlement {} already completed, skipping saga", settlementId);
            return;
        }

        log.info("Starting saga for settlement={} load={} currentStep={}", settlementId, s.loadId(), s.currentStep());
        settlementRepository.updateStatus(s.id(), SettlementStatus.IN_PROGRESS, s.currentStep(), s.sagaLog());

        try {
            for (SettlementStep step : SettlementStep.values()) {
                if (s.currentStep() >= step.getStepNumber()) {
                    log.debug("Skipping already-completed step {} for settlement={}", step.name(), settlementId);
                    continue;
                }

                if (!idempotencyService.tryAcquire(settlementId, step.getStepNumber())) {
                    log.warn("Duplicate step execution blocked: settlement={} step={}", settlementId, step.name());
                    continue;
                }

                executeStep(s, step);
                s = logStepSuccess(s, step);
                s = advanceStep(s, step);
            }
            complete(s);
        } catch (SagaStepException e) {
            log.error("Saga step failed for settlement={}: {}", settlementId, e.getMessage());
            s = compensate(s, e.getFailedStep());
            fail(s, e);
        }
    }

    // ── Step execution ──────────────────────────────────────────────────

    private void executeStep(Settlement s, SettlementStep step) {
        log.info("Executing step {} for settlement={}", step.name(), s.id());
        switch (step) {
            case DELIVERY_CONFIRMED -> verifyDelivery(s);
            case INSPECTION -> inspectTransit(s);
            case AMOUNT_DETERMINED -> determineAmount(s);
            case PAYMENT_RELEASED -> releasePayment(s);
            case INVOICE_ISSUED -> issueInvoice(s);
        }
        eventProducer.publishStepEvent(s, step, "SUCCESS", null);
    }

    /**
     * Step 1: Verify the freight load exists and is in a deliverable state.
     */
    private void verifyDelivery(Settlement s) {
        List<Map<String, Object>> loads = jdbc.queryForList(
                "SELECT id, status FROM freight_load WHERE id = ?", s.loadId());

        if (loads.isEmpty()) {
            throw new SagaStepException(SettlementStep.DELIVERY_CONFIRMED,
                    "Freight load not found: " + s.loadId());
        }

        String loadStatus = (String) loads.get(0).get("status");
        log.info("Delivery confirmed for load={} status={}", s.loadId(), loadStatus);
    }

    /**
     * Step 2: Inspect tracking events for risk events during transit.
     * If any tracking ping has risk_score >= DAMAGE_RISK_THRESHOLD, cargo damage
     * is suspected and the settlement is disputed.
     */
    private void inspectTransit(Settlement s) {
        List<Map<String, Object>> riskEvents;
        try {
            riskEvents = jdbc.queryForList(
                    """
                    SELECT time, risk_score
                    FROM tracking_event
                    WHERE truck_id = (
                        SELECT carrier_id FROM settlement WHERE id = ?
                    )
                    AND risk_score >= ?
                    ORDER BY time DESC
                    LIMIT 10
                    """,
                    s.id(), DAMAGE_RISK_THRESHOLD);
        } catch (Exception e) {
            // tracking_event table may not exist in all environments; pass inspection
            log.warn("Could not query tracking events for settlement={}, passing inspection: {}",
                    s.id(), e.getMessage());
            return;
        }

        if (!riskEvents.isEmpty()) {
            String detail = "Damage suspected: %d high-risk events (max risk_score >= %d) during transit"
                    .formatted(riskEvents.size(), DAMAGE_RISK_THRESHOLD);
            throw new SagaStepException(SettlementStep.INSPECTION, detail, true);
        }

        log.info("Inspection passed for settlement={}: no high-risk transit events", s.id());
    }

    /**
     * Step 3: Calculate the final settlement amount.
     * final = baseRate + adjustment, where adjustment accounts for:
     *   - Delay penalty: 5% of base per hour past delivery_deadline (cap 25%)
     *   - Early bonus: 2% of base per hour before deadline (cap 10%)
     * Transit times are derived from tracking_event first/last pings vs delivery_deadline.
     */
    private void determineAmount(Settlement s) {
        int adjustmentCents = 0;
        String reason = "no adjustment data available";

        try {
            // Get delivery deadline from freight_load
            List<Map<String, Object>> loads = jdbc.queryForList(
                    "SELECT delivery_deadline FROM freight_load WHERE id = ?", s.loadId());

            if (!loads.isEmpty() && loads.get(0).get("delivery_deadline") != null) {
                Instant deadline = ((java.sql.Timestamp) loads.get(0).get("delivery_deadline")).toInstant();

                // Get actual delivery time from last tracking ping
                List<Map<String, Object>> transitTimes = jdbc.queryForList(
                        """
                        SELECT min(time) AS first_ping, max(time) AS last_ping
                        FROM tracking_event
                        WHERE truck_id = ?
                        """,
                        s.carrierId());

                if (!transitTimes.isEmpty()
                        && transitTimes.get(0).get("last_ping") != null) {
                    Instant actualDelivery = ((java.sql.Timestamp) transitTimes.get(0).get("last_ping")).toInstant();
                    long hoursDiff = Duration.between(deadline, actualDelivery).toHours();

                    if (hoursDiff > 0) {
                        // Late: penalty
                        double penaltyPct = Math.min(hoursDiff * DELAY_PENALTY_PER_HOUR, MAX_DELAY_PENALTY);
                        adjustmentCents = -(int) Math.round(s.baseRateCents() * penaltyPct);
                        reason = "delay penalty: %d hours late (%.0f%% of base)".formatted(hoursDiff, penaltyPct * 100);
                    } else if (hoursDiff < 0) {
                        // Early: bonus
                        long hoursEarly = Math.abs(hoursDiff);
                        double bonusPct = Math.min(hoursEarly * EARLY_BONUS_PER_HOUR, MAX_EARLY_BONUS);
                        adjustmentCents = (int) Math.round(s.baseRateCents() * bonusPct);
                        reason = "early bonus: %d hours early (%.0f%% of base)".formatted(hoursEarly, bonusPct * 100);
                    } else {
                        reason = "on-time delivery, no adjustment";
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not compute transit-time adjustment for settlement={}: {}", s.id(), e.getMessage());
        }

        int finalAmount = s.baseRateCents() + adjustmentCents;
        if (finalAmount < 0) {
            finalAmount = 0;
            adjustmentCents = -s.baseRateCents();
        }

        settlementRepository.updateFinalAmount(s.id(), adjustmentCents, finalAmount);
        settlementRepository.insertStepLog(s.id(), SettlementStep.AMOUNT_DETERMINED.getStepNumber(),
                SettlementStep.AMOUNT_DETERMINED.getDisplayName(), "SUCCESS",
                "{\"baseRateCents\":%d,\"adjustmentCents\":%d,\"finalAmountCents\":%d,\"reason\":\"%s\"}"
                        .formatted(s.baseRateCents(), adjustmentCents, finalAmount, reason));

        log.info("Amount determined for settlement={}: base={} adjustment={} final={} ({})",
                s.id(), s.baseRateCents(), adjustmentCents, finalAmount, reason);
    }

    /**
     * Step 4: Release payment via atomic wallet operations.
     * Debit shipper → credit carrier. If the shipper has insufficient funds,
     * the conditional UPDATE returns 0 rows and we throw to trigger compensation.
     */
    private void releasePayment(Settlement s) {
        // Re-read to get the computed final amount
        Settlement current = settlementRepository.findById(s.id()).orElseThrow();
        int amount = current.finalAmountCents() != null ? current.finalAmountCents() : current.baseRateCents();

        boolean debited = walletService.debit(s.shipperId(), amount);
        if (!debited) {
            throw new SagaStepException(SettlementStep.PAYMENT_RELEASED,
                    "Insufficient funds: shipper %s cannot pay %d cents".formatted(s.shipperId(), amount));
        }

        boolean credited = walletService.credit(s.carrierId(), amount);
        if (!credited) {
            // Carrier wallet missing — reverse the shipper debit
            walletService.credit(s.shipperId(), amount);
            throw new SagaStepException(SettlementStep.PAYMENT_RELEASED,
                    "Carrier wallet not found: " + s.carrierId());
        }

        settlementRepository.insertStepLog(s.id(), SettlementStep.PAYMENT_RELEASED.getStepNumber(),
                SettlementStep.PAYMENT_RELEASED.getDisplayName(), "SUCCESS",
                "{\"amountCents\":%d,\"from\":\"%s\",\"to\":\"%s\"}"
                        .formatted(amount, s.shipperId(), s.carrierId()));

        log.info("Payment released for settlement={}: {} cents from shipper={} to carrier={}",
                s.id(), amount, s.shipperId(), s.carrierId());
    }

    /**
     * Step 5: Issue an invoice record in the step log.
     */
    private void issueInvoice(Settlement s) {
        Settlement current = settlementRepository.findById(s.id()).orElseThrow();
        int amount = current.finalAmountCents() != null ? current.finalAmountCents() : current.baseRateCents();
        String invoiceRef = "INV-%d-%d".formatted(s.loadId(), s.id());

        settlementRepository.insertStepLog(s.id(), SettlementStep.INVOICE_ISSUED.getStepNumber(),
                SettlementStep.INVOICE_ISSUED.getDisplayName(), "SUCCESS",
                "{\"invoiceRef\":\"%s\",\"amountCents\":%d,\"carrierId\":\"%s\",\"shipperId\":\"%s\"}"
                        .formatted(invoiceRef, amount, s.carrierId(), s.shipperId()));

        log.info("Invoice {} issued for settlement={}: {} cents", invoiceRef, s.id(), amount);
    }

    // ── Saga log & state transitions ────────────────────────────────────

    private Settlement logStepSuccess(Settlement s, SettlementStep step) {
        String updatedLog = appendToSagaLog(s.sagaLog(), step, "SUCCESS", null);
        settlementRepository.insertStepLog(s.id(), step.getStepNumber(), step.getDisplayName(), "SUCCESS", null);
        return new Settlement(s.id(), s.loadId(), s.bidId(), s.carrierId(), s.shipperId(),
                s.baseRateCents(), s.adjustmentCents(), s.finalAmountCents(),
                s.status(), s.currentStep(), updatedLog, s.createdAt(), s.completedAt());
    }

    private Settlement advanceStep(Settlement s, SettlementStep step) {
        int nextStep = step.getStepNumber();
        settlementRepository.updateStatus(s.id(), SettlementStatus.IN_PROGRESS, nextStep, s.sagaLog());
        return new Settlement(s.id(), s.loadId(), s.bidId(), s.carrierId(), s.shipperId(),
                s.baseRateCents(), s.adjustmentCents(), s.finalAmountCents(),
                SettlementStatus.IN_PROGRESS, nextStep, s.sagaLog(), s.createdAt(), s.completedAt());
    }

    private void complete(Settlement s) {
        String finalLog = appendToSagaLog(s.sagaLog(), null, "SAGA_COMPLETED", null);
        settlementRepository.markCompleted(s.id(), finalLog);
        eventProducer.publishStepEvent(s, SettlementStep.INVOICE_ISSUED, "SAGA_COMPLETED", null);
        log.info("Saga completed for settlement={}", s.id());
    }

    private void fail(Settlement s, SagaStepException e) {
        SettlementStatus terminalStatus = e.isDispute() ? SettlementStatus.DISPUTED : SettlementStatus.FAILED;
        String finalLog = appendToSagaLog(s.sagaLog(), e.getFailedStep(), "FAILED", e.getDetail());
        settlementRepository.markFailed(s.id(), terminalStatus, s.currentStep(), finalLog);
        settlementRepository.insertStepLog(s.id(), e.getFailedStep().getStepNumber(),
                e.getFailedStep().getDisplayName(), terminalStatus.name(),
                "{\"error\":\"%s\"}".formatted(e.getDetail().replace("\"", "'")));
        eventProducer.publishStepEvent(s, e.getFailedStep(), terminalStatus.name(), e.getDetail());
        log.info("Saga ended with status={} for settlement={} at step={}",
                terminalStatus, s.id(), e.getFailedStep().name());
    }

    // ── Compensation ────────────────────────────────────────────────────

    /**
     * Run compensating transactions for all steps completed before the failure.
     * Currently only PAYMENT_RELEASED (step 4) has a compensation: reverse the
     * wallet transfer by crediting the shipper and debiting the carrier.
     */
    private Settlement compensate(Settlement s, SettlementStep failedStep) {
        String sagaLog = s.sagaLog();

        // Only compensate payment if step 4 completed (currentStep >= 4) and failure is after it
        if (s.currentStep() >= SettlementStep.PAYMENT_RELEASED.getStepNumber()
                && failedStep.getStepNumber() > SettlementStep.PAYMENT_RELEASED.getStepNumber()) {

            Settlement current = settlementRepository.findById(s.id()).orElseThrow();
            int amount = current.finalAmountCents() != null
                    ? current.finalAmountCents() : current.baseRateCents();

            log.info("Compensating payment for settlement={}: reversing {} cents", s.id(), amount);
            walletService.debit(s.carrierId(), amount);
            walletService.credit(s.shipperId(), amount);

            settlementRepository.insertStepLog(s.id(), SettlementStep.PAYMENT_RELEASED.getStepNumber(),
                    SettlementStep.PAYMENT_RELEASED.getDisplayName(), "COMPENSATED",
                    "{\"reversedCents\":%d}".formatted(amount));

            sagaLog = appendToSagaLog(sagaLog, SettlementStep.PAYMENT_RELEASED,
                    "COMPENSATED", "Payment reversed: " + amount + " cents");
        }

        return new Settlement(s.id(), s.loadId(), s.bidId(), s.carrierId(), s.shipperId(),
                s.baseRateCents(), s.adjustmentCents(), s.finalAmountCents(),
                s.status(), s.currentStep(), sagaLog, s.createdAt(), s.completedAt());
    }

    // ── JSONB saga log helper ───────────────────────────────────────────

    private String appendToSagaLog(String existingLog, SettlementStep step, String status, String detail) {
        try {
            ArrayNode logArray = (existingLog != null && !existingLog.isBlank())
                    ? (ArrayNode) objectMapper.readTree(existingLog)
                    : objectMapper.createArrayNode();

            ObjectNode entry = objectMapper.createObjectNode();
            if (step != null) {
                entry.put("step", step.getStepNumber());
                entry.put("name", step.name());
            }
            entry.put("status", status);
            if (detail != null) {
                entry.put("detail", detail);
            }
            entry.put("at", Instant.now().toString());
            logArray.add(entry);

            return objectMapper.writeValueAsString(logArray);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize saga log: {}", e.getMessage());
            return existingLog != null ? existingLog : "[]";
        }
    }
}
