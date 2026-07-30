package com.freightscaler.ranking.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freightscaler.ranking.model.BidEvent;
import com.freightscaler.ranking.model.ShipmentEvent;
import com.freightscaler.ranking.repository.LoadLookupRepository;
import com.freightscaler.ranking.service.RankingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Turns platform events into carrier ranking updates.
 *
 * <p>Scoring policy:
 * <ul>
 *   <li>bid-events / BID_ACCEPTED — winning a bid: +10 on the load's corridor and overall</li>
 *   <li>shipment-events / DELIVERED, onTime=true — +5 corridor and overall</li>
 *   <li>shipment-events / DELIVERED, onTime=false — -3 corridor and overall</li>
 *   <li>telemetry-raw — subscribed for future risk-exposure scoring; volume only for now</li>
 * </ul>
 *
 * <p>Payloads arrive as raw JSON strings (see {@link com.freightscaler.ranking.config.KafkaConfig})
 * and are parsed per topic with unknown fields ignored.
 */
@Component
public class RankingEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RankingEventConsumer.class);

    static final String CATEGORY_CORRIDOR = "corridor";
    static final String CATEGORY_OVERALL = "overall";

    static final double BID_WIN_DELTA = 10.0;
    static final double ON_TIME_DELIVERY_DELTA = 5.0;
    static final double LATE_DELIVERY_DELTA = -3.0;

    private static final long TELEMETRY_LOG_INTERVAL = 10_000;

    private final RankingService rankingService;
    private final LoadLookupRepository loadLookup;
    private final ObjectMapper objectMapper;
    private final AtomicLong telemetrySeen = new AtomicLong();

    public RankingEventConsumer(
            RankingService rankingService,
            LoadLookupRepository loadLookup,
            ObjectMapper objectMapper) {
        this.rankingService = rankingService;
        this.loadLookup = loadLookup;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = {"bid-events", "shipment-events", "telemetry-raw"}, groupId = "ranking-svc")
    public void onMessage(ConsumerRecord<String, String> record) {
        try {
            switch (record.topic()) {
                case "bid-events" -> handleBid(record.value());
                case "shipment-events" -> handleShipment(record.value());
                case "telemetry-raw" -> handleTelemetry();
                default -> log.warn("Received record from unexpected topic {}-{}@{}",
                        record.topic(), record.partition(), record.offset());
            }
        } catch (Exception e) {
            // Poison-pill guard: log and commit rather than blocking the partition.
            log.error("Failed to process record {}-{}@{}: {}",
                    record.topic(), record.partition(), record.offset(), e.getMessage(), e);
        }
    }

    private void handleBid(String payload) throws Exception {
        BidEvent event = objectMapper.readValue(payload, BidEvent.class);
        if (!"BID_ACCEPTED".equals(event.type())) {
            return; // only winning a bid changes rankings
        }
        if (event.carrierId() == null) {
            log.warn("BID_ACCEPTED without carrierId (bidId={}); skipped", event.bidId());
            return;
        }

        Optional<String> corridor = loadLookup.findCorridorByLoadId(event.loadId());
        if (corridor.isPresent()) {
            double corridorScore = rankingService.incrementScore(
                    CATEGORY_CORRIDOR, corridor.get(), event.carrierId(), BID_WIN_DELTA);
            log.info("BID_ACCEPTED carrier={} corridor={} delta=+{} -> corridorScore={}",
                    event.carrierId(), corridor.get(), BID_WIN_DELTA, corridorScore);
        } else {
            log.warn("BID_ACCEPTED carrier={} load={} has no corridor; corridor score skipped",
                    event.carrierId(), event.loadId());
        }

        double overallScore = rankingService.incrementScore(
                CATEGORY_OVERALL, null, event.carrierId(), BID_WIN_DELTA);
        log.info("BID_ACCEPTED carrier={} delta=+{} -> overallScore={}",
                event.carrierId(), BID_WIN_DELTA, overallScore);
    }

    private void handleShipment(String payload) throws Exception {
        ShipmentEvent event = objectMapper.readValue(payload, ShipmentEvent.class);
        if (!"DELIVERED".equals(event.type())) {
            return; // only delivery completion affects rankings
        }
        if (event.carrierId() == null) {
            log.warn("DELIVERED event without carrierId (shipmentId={}); skipped", event.shipmentId());
            return;
        }

        boolean onTime = Boolean.TRUE.equals(event.onTime());
        double delta = onTime ? ON_TIME_DELIVERY_DELTA : LATE_DELIVERY_DELTA;

        if (event.corridorId() != null && !event.corridorId().isBlank()) {
            double corridorScore = rankingService.incrementScore(
                    CATEGORY_CORRIDOR, event.corridorId(), event.carrierId(), delta);
            log.info("DELIVERED carrier={} corridor={} onTime={} delta={} -> corridorScore={}",
                    event.carrierId(), event.corridorId(), onTime, delta, corridorScore);
        } else {
            log.warn("DELIVERED shipment={} carrier={} has no corridor; corridor score skipped",
                    event.shipmentId(), event.carrierId());
        }

        double overallScore = rankingService.incrementScore(
                CATEGORY_OVERALL, null, event.carrierId(), delta);
        log.info("DELIVERED carrier={} onTime={} delta={} -> overallScore={}",
                event.carrierId(), onTime, delta, overallScore);
    }

    /**
     * telemetry-raw is subscribed now so risk-exposure scoring can be added without
     * re-deploying consumer group topology. Until the risk policy is defined (and a
     * truck -> carrier mapping exists) this only tracks volume.
     */
    private void handleTelemetry() {
        long seen = telemetrySeen.incrementAndGet();
        if (seen % TELEMETRY_LOG_INTERVAL == 0) {
            log.info("telemetry-raw volume seen={} (no scoring until risk-exposure policy is defined)", seen);
        }
    }
}
