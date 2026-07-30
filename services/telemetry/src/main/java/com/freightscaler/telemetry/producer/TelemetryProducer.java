package com.freightscaler.telemetry.producer;

import com.freightscaler.telemetry.model.TelemetryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Publishes TelemetryEvents to the raw telemetry Kafka topic.
 * Partition key = truckId ensures per-truck ordering.
 */
@Component
public class TelemetryProducer {

    private static final Logger log = LoggerFactory.getLogger(TelemetryProducer.class);

    private final KafkaTemplate<String, TelemetryEvent> kafkaTemplate;
    private final String topic;

    public TelemetryProducer(
            KafkaTemplate<String, TelemetryEvent> kafkaTemplate,
            @Value("${telemetry.topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public CompletableFuture<SendResult<String, TelemetryEvent>> send(TelemetryEvent event) {
        String key = event.truckId().toString();
        CompletableFuture<SendResult<String, TelemetryEvent>> future =
                kafkaTemplate.send(topic, key, event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish telemetry for truck {} (trace={}): {}",
                        event.truckId(), event.traceId(), ex.getMessage(), ex);
            } else {
                int partition = result.getRecordMetadata().partition();
                long offset = result.getRecordMetadata().offset();
                log.debug("Published telemetry for truck {} to {}-{} (trace={})",
                        event.truckId(), topic, partition, offset, event.traceId());
            }
        });

        return future;
    }
}
