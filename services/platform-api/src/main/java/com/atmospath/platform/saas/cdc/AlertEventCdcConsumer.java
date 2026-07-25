package com.atmospath.platform.saas.cdc;

import java.util.UUID;

import com.atmospath.platform.saas.entity.AlertEvent;
import com.atmospath.platform.saas.redis.RedisAlertPubSub;
import com.atmospath.platform.saas.repository.AlertEventRepository;
import com.atmospath.platform.saas.service.AlertEscalationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the Debezium change stream for {@code public.alert_event}.
 * INSERTs in TRIGGERED state run the notification workflow; UPDATEs into
 * ACKNOWLEDGED/RESOLVED are fanned out to SSE subscribers through Redis
 * Pub/Sub. Poison records land on the dead-letter topic via
 * {@link CdcKafkaConfig}.
 */
@Component
@ConditionalOnProperty(name = {"atmospath.saas.enabled", "atmospath.cdc.enabled"}, havingValue = "true")
public class AlertEventCdcConsumer {

    static final String TOPIC = "atmospath.public.alert_event";

    private static final Logger log = LoggerFactory.getLogger(AlertEventCdcConsumer.class);

    private final AlertEscalationService alertEscalationService;
    private final AlertEventRepository alertEventRepository;
    private final RedisAlertPubSub redisAlertPubSub;
    private final ObjectMapper objectMapper;

    public AlertEventCdcConsumer(AlertEscalationService alertEscalationService,
                                 AlertEventRepository alertEventRepository,
                                 RedisAlertPubSub redisAlertPubSub,
                                 ObjectMapper objectMapper) {
        this.alertEscalationService = alertEscalationService;
        this.alertEventRepository = alertEventRepository;
        this.redisAlertPubSub = redisAlertPubSub;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(id = "alert-event-cdc", topics = TOPIC, containerFactory = CdcKafkaConfig.CONTAINER_FACTORY)
    public void onAlertEvent(String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception ex) {
            throw new IllegalStateException("Unparseable CDC envelope; sending to DLT", ex);
        }
        JsonNode payload = envelope.has("payload") ? envelope.get("payload") : envelope;
        String operation = payload.path("op").asText("");
        JsonNode after = payload.path("after");

        switch (operation) {
            case "c", "r" -> handleInsert(after);
            case "u" -> handleUpdate(after);
            case "d" -> log.debug("alert_event delete event ignored");
            default -> log.warn("Unknown CDC operation '{}' on {}", operation, TOPIC);
        }
    }

    private void handleInsert(JsonNode after) {
        if (after.isMissingNode() || after.isNull()) {
            return;
        }
        String state = after.path("state").asText("");
        if (!"TRIGGERED".equals(state)) {
            return;
        }
        UUID eventId = parseId(after.path("id").asText(null));
        if (eventId == null) {
            return;
        }
        log.info("CDC: alert {} triggered; starting notification workflow", eventId);
        alertEscalationService.handleTriggeredEvent(eventId);
    }

    private void handleUpdate(JsonNode after) {
        if (after.isMissingNode() || after.isNull()) {
            return;
        }
        String state = after.path("state").asText("");
        if (!"ACKNOWLEDGED".equals(state) && !"RESOLVED".equals(state) && !"ESCALATED".equals(state)) {
            return;
        }
        UUID eventId = parseId(after.path("id").asText(null));
        if (eventId == null) {
            return;
        }
        AlertEvent event = alertEventRepository.findById(eventId).orElse(null);
        if (event == null) {
            log.warn("CDC update for unknown alert event {}", eventId);
            return;
        }
        log.info("CDC: alert {} moved to {}; notifying SSE subscribers", eventId, state);
        redisAlertPubSub.publish(event);
    }

    private UUID parseId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            log.warn("CDC record without a usable alert_event id");
            return null;
        }
        try {
            return UUID.fromString(rawId);
        } catch (IllegalArgumentException ex) {
            log.warn("CDC record with malformed alert_event id: {}", rawId);
            return null;
        }
    }
}
