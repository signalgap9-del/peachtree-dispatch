package com.atmospath.platform.saas.redis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.atmospath.platform.saas.entity.AlertEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes alert state changes to {@code alerts:{tenantId}} channels.
 * The SSE layer subscribes through {@code AlertStreamPubSubBridge} instead
 * of polling when {@code atmospath.alert-stream.mode=pubsub}.
 */
@Component
@ConditionalOnProperty(name = "atmospath.saas.enabled", havingValue = "true")
public class RedisAlertPubSub {

    public static final String CHANNEL_PREFIX = "alerts:";

    private static final Logger log = LoggerFactory.getLogger(RedisAlertPubSub.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisAlertPubSub(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public static String channel(UUID tenantId) {
        return CHANNEL_PREFIX + tenantId;
    }

    public void publish(AlertEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", event.getId());
        payload.put("savedRouteId", event.getSavedRouteId());
        payload.put("tenantId", event.getTenantId());
        payload.put("state", event.getState() == null ? null : event.getState().name());
        payload.put("severity", event.getSeverity() == null ? null : event.getSeverity().name());
        payload.put("riskScore", event.getRiskScore());
        payload.put("triggeredAt", event.getTriggeredAt());
        payload.put("notifiedAt", event.getNotifiedAt());
        payload.put("escalatedAt", event.getEscalatedAt());
        payload.put("acknowledgedAt", event.getAcknowledgedAt());
        payload.put("resolvedAt", event.getResolvedAt());
        try {
            redis.convertAndSend(channel(event.getTenantId()), objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize alert payload for event {}", event.getId(), ex);
        } catch (RuntimeException ex) {
            // Pub/Sub is best-effort: SSE falls back to polling when Redis is unavailable.
            log.warn("Redis publish failed for alert event {}: {}", event.getId(), ex.toString());
        }
    }
}
