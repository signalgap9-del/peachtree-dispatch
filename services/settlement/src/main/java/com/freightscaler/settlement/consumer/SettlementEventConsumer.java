package com.freightscaler.settlement.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.freightscaler.settlement.model.Settlement;
import com.freightscaler.settlement.model.SettlementStatus;
import com.freightscaler.settlement.repository.SettlementRepository;
import com.freightscaler.settlement.service.SettlementSagaOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Kafka consumer that reacts to upstream domain events:
 *
 * 1. bid-events (BID_ACCEPTED) → creates a settlement record in PENDING status,
 *    pre-populated with the bid's rate and parties.
 * 2. shipment-events (DELIVERED) → triggers the 5-step saga execution for the
 *    settlement associated with the delivered load.
 *
 * Uses JsonNode payloads so the consumer is decoupled from upstream event classes.
 */
@Component
public class SettlementEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(SettlementEventConsumer.class);

    private final SettlementRepository settlementRepository;
    private final SettlementSagaOrchestrator sagaOrchestrator;

    public SettlementEventConsumer(
            SettlementRepository settlementRepository,
            SettlementSagaOrchestrator sagaOrchestrator
    ) {
        this.settlementRepository = settlementRepository;
        this.sagaOrchestrator = sagaOrchestrator;
    }

    /**
     * Listens for BID_ACCEPTED events and creates a PENDING settlement.
     * Expected fields: bidId, loadId, carrierId, shipperId (or tenantId), rateCents.
     */
    @KafkaListener(topics = "bid-events", groupId = "settlement-svc")
    public void onBidEvent(JsonNode event) {
        String type = event.has("type") ? event.get("type").asText() : "";
        if (!"BID_ACCEPTED".equals(type)) {
            log.debug("Ignoring non-BID_ACCEPTED event type={}", type);
            return;
        }

        Long loadId = event.get("loadId").asLong();
        Long bidId = event.get("bidId").asLong();

        // Idempotent: skip if a settlement already exists for this load
        Optional<Settlement> existing = settlementRepository.findByLoadId(loadId);
        if (existing.isPresent()) {
            log.info("Settlement already exists for load={} (id={}), skipping creation",
                    loadId, existing.get().id());
            return;
        }

        UUID carrierId = UUID.fromString(event.get("carrierId").asText());
        // shipperId may come as shipperId or tenantId depending on the upstream event version
        UUID shipperId = event.has("shipperId")
                ? UUID.fromString(event.get("shipperId").asText())
                : UUID.fromString(event.get("tenantId").asText());
        int rateCents = event.has("rateCents") ? event.get("rateCents").asInt() : 0;

        Settlement settlement = new Settlement(
                null, loadId, bidId, carrierId, shipperId,
                rateCents, 0, null,
                SettlementStatus.PENDING, 0, "[]",
                Instant.now(), null
        );

        Settlement inserted = settlementRepository.insert(settlement);
        log.info("Settlement created: id={} load={} bid={} carrier={} status=PENDING",
                inserted.id(), loadId, bidId, carrierId);
    }

    /**
     * Listens for DELIVERED events and triggers the settlement saga.
     * Expected fields: loadId.
     */
    @KafkaListener(topics = "shipment-events", groupId = "settlement-svc")
    public void onShipmentEvent(JsonNode event) {
        String type = event.has("type") ? event.get("type").asText() : "";
        if (!"DELIVERED".equals(type)) {
            log.debug("Ignoring non-DELIVERED event type={}", type);
            return;
        }

        Long loadId = event.get("loadId").asLong();
        Optional<Settlement> settlement = settlementRepository.findByLoadId(loadId);

        if (settlement.isEmpty()) {
            log.warn("No settlement found for delivered load={}, cannot trigger saga", loadId);
            return;
        }

        Settlement s = settlement.get();
        if (s.status() == SettlementStatus.COMPLETED || s.status() == SettlementStatus.IN_PROGRESS) {
            log.info("Settlement {} for load={} already {}, skipping saga trigger",
                    s.id(), loadId, s.status());
            return;
        }

        log.info("DELIVERED event received for load={}, triggering saga for settlement={}", loadId, s.id());
        sagaOrchestrator.executeSaga(s.id());
    }
}
