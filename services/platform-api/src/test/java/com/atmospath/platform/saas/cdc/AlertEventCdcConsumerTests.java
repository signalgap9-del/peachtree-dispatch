package com.atmospath.platform.saas.cdc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.atmospath.platform.saas.entity.AlertEvent;
import com.atmospath.platform.saas.entity.AlertSeverity;
import com.atmospath.platform.saas.redis.RedisAlertPubSub;
import com.atmospath.platform.saas.repository.AlertEventRepository;
import com.atmospath.platform.saas.service.AlertEscalationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AlertEventCdcConsumer}. Exercises the CDC message-handling
 * logic by calling {@code onAlertEvent} directly with constructed Debezium
 * envelope JSON, mocking all downstream services.
 */
class AlertEventCdcConsumerTests {

    private AlertEscalationService escalationService;
    private AlertEventRepository alertEventRepository;
    private RedisAlertPubSub redisAlertPubSub;
    private AlertEventCdcConsumer consumer;

    @BeforeEach
    void setUp() {
        escalationService = mock(AlertEscalationService.class);
        alertEventRepository = mock(AlertEventRepository.class);
        redisAlertPubSub = mock(RedisAlertPubSub.class);
        consumer = new AlertEventCdcConsumer(
                escalationService, alertEventRepository,
                redisAlertPubSub, new ObjectMapper());
    }

    private String cdcEnvelope(String op, String afterJson) {
        if (afterJson == null) {
            return "{\"payload\":{\"op\":\"%s\"}}".formatted(op);
        }
        return "{\"payload\":{\"op\":\"%s\",\"after\":%s}}".formatted(op, afterJson);
    }

    private String alertJson(UUID id, String state) {
        return "{\"id\":\"%s\",\"state\":\"%s\"}".formatted(id, state);
    }

    @Test
    void insertTriggeredStartsEscalationWorkflow() {
        UUID eventId = UUID.randomUUID();
        String message = cdcEnvelope("c", alertJson(eventId, "TRIGGERED"));

        consumer.onAlertEvent(message);

        verify(escalationService).handleTriggeredEvent(eventId);
    }

    @Test
    void snapshotReadAlsoTriggersEscalation() {
        UUID eventId = UUID.randomUUID();
        String message = cdcEnvelope("r", alertJson(eventId, "TRIGGERED"));

        consumer.onAlertEvent(message);

        verify(escalationService).handleTriggeredEvent(eventId);
    }

    @Test
    void insertNonTriggeredStateIsIgnored() {
        UUID eventId = UUID.randomUUID();
        String message = cdcEnvelope("c", alertJson(eventId, "ACKNOWLEDGED"));

        consumer.onAlertEvent(message);

        verify(escalationService, never()).handleTriggeredEvent(any());
    }

    @Test
    void insertWithNullAfterIsIgnored() {
        String message = "{\"payload\":{\"op\":\"c\",\"after\":null}}";

        consumer.onAlertEvent(message);

        verify(escalationService, never()).handleTriggeredEvent(any());
    }

    @Test
    void insertWithMissingAfterIsIgnored() {
        String message = "{\"payload\":{\"op\":\"c\"}}";

        consumer.onAlertEvent(message);

        verify(escalationService, never()).handleTriggeredEvent(any());
    }

    @Test
    void insertWithMalformedIdIsIgnored() {
        String message = cdcEnvelope("c",
                "{\"id\":\"not-a-uuid\",\"state\":\"TRIGGERED\"}");

        consumer.onAlertEvent(message);

        verify(escalationService, never()).handleTriggeredEvent(any());
    }

    @Test
    void insertWithBlankIdIsIgnored() {
        String message = cdcEnvelope("c",
                "{\"id\":\"\",\"state\":\"TRIGGERED\"}");

        consumer.onAlertEvent(message);

        verify(escalationService, never()).handleTriggeredEvent(any());
    }

    @Test
    void updateToAcknowledgedPublishesToRedis() {
        UUID eventId = UUID.randomUUID();
        AlertEvent event = new AlertEvent(UUID.randomUUID(), UUID.randomUUID(),
                AlertSeverity.HIGH, 85);
        when(alertEventRepository.findById(eventId)).thenReturn(Optional.of(event));

        String message = cdcEnvelope("u", alertJson(eventId, "ACKNOWLEDGED"));
        consumer.onAlertEvent(message);

        verify(redisAlertPubSub).publish(event);
    }

    @Test
    void updateToResolvedPublishesToRedis() {
        UUID eventId = UUID.randomUUID();
        AlertEvent event = new AlertEvent(UUID.randomUUID(), UUID.randomUUID(),
                AlertSeverity.LOW, 30);
        when(alertEventRepository.findById(eventId)).thenReturn(Optional.of(event));

        String message = cdcEnvelope("u", alertJson(eventId, "RESOLVED"));
        consumer.onAlertEvent(message);

        verify(redisAlertPubSub).publish(event);
    }

    @Test
    void updateToEscalatedPublishesToRedis() {
        UUID eventId = UUID.randomUUID();
        AlertEvent event = new AlertEvent(UUID.randomUUID(), UUID.randomUUID(),
                AlertSeverity.SEVERE, 95);
        when(alertEventRepository.findById(eventId)).thenReturn(Optional.of(event));

        String message = cdcEnvelope("u", alertJson(eventId, "ESCALATED"));
        consumer.onAlertEvent(message);

        verify(redisAlertPubSub).publish(event);
    }

    @Test
    void updateToTriggeredDoesNotPublish() {
        UUID eventId = UUID.randomUUID();
        String message = cdcEnvelope("u", alertJson(eventId, "TRIGGERED"));

        consumer.onAlertEvent(message);

        verify(redisAlertPubSub, never()).publish(any());
    }

    @Test
    void updateForUnknownEventLogsWarningAndSkips() {
        UUID eventId = UUID.randomUUID();
        when(alertEventRepository.findById(eventId)).thenReturn(Optional.empty());

        String message = cdcEnvelope("u", alertJson(eventId, "ACKNOWLEDGED"));
        consumer.onAlertEvent(message);

        verify(redisAlertPubSub, never()).publish(any());
    }

    @Test
    void deleteOperationIsIgnored() {
        String message = cdcEnvelope("d", null);

        consumer.onAlertEvent(message);

        verify(escalationService, never()).handleTriggeredEvent(any());
        verify(redisAlertPubSub, never()).publish(any());
    }

    @Test
    void unknownOperationIsIgnored() {
        String message = cdcEnvelope("x", null);

        consumer.onAlertEvent(message);

        verify(escalationService, never()).handleTriggeredEvent(any());
        verify(redisAlertPubSub, never()).publish(any());
    }

    @Test
    void unparseableMessageThrowsForDlt() {
        assertThatThrownBy(() -> consumer.onAlertEvent("{invalid json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unparseable CDC envelope");
    }

    @Test
    void envelopeWithoutPayloadWrapperIsHandled() {
        UUID eventId = UUID.randomUUID();
        // Some connectors send the payload directly without a wrapper
        String message = "{\"op\":\"c\",\"after\":%s}".formatted(alertJson(eventId, "TRIGGERED"));

        consumer.onAlertEvent(message);

        verify(escalationService).handleTriggeredEvent(eventId);
    }

    @Test
    void topicConstantIsCorrect() {
        org.assertj.core.api.Assertions.assertThat(AlertEventCdcConsumer.TOPIC)
                .isEqualTo("atmospath.public.alert_event");
    }
}
