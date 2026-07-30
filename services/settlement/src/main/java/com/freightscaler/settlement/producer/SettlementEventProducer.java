package com.freightscaler.settlement.producer;

import com.freightscaler.settlement.model.Settlement;
import com.freightscaler.settlement.model.SettlementEvent;
import com.freightscaler.settlement.model.SettlementStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes settlement events to Kafka. Key = settlementId ensures
 * per-settlement partition ordering.
 */
@Component
public class SettlementEventProducer {

    private static final Logger log = LoggerFactory.getLogger(SettlementEventProducer.class);

    private final KafkaTemplate<String, SettlementEvent> kafkaTemplate;
    private final String topic;

    public SettlementEventProducer(
            KafkaTemplate<String, SettlementEvent> kafkaTemplate,
            @Value("${settlement.topic:settlement-events}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(SettlementEvent event) {
        String key = String.valueOf(event.settlementId());
        kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish settlement event settlementId={} type={}: {}",
                                event.settlementId(), event.type(), ex.getMessage(), ex);
                    } else {
                        log.debug("Published settlement event settlementId={} type={} to partition={} offset={}",
                                event.settlementId(), event.type(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }

    /**
     * Convenience method used by the saga orchestrator to publish step-level events.
     */
    public void publishStepEvent(Settlement s, SettlementStep step, String status, String detail) {
        SettlementEvent event = new SettlementEvent(
                s.id(),
                s.loadId(),
                "SETTLEMENT_" + status,
                step.name(),
                detail,
                UUID.randomUUID().toString(),
                Instant.now()
        );
        publish(event);
    }
}
