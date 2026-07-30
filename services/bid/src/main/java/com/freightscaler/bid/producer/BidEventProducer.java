package com.freightscaler.bid.producer;

import com.freightscaler.bid.model.BidEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes bid events to Kafka. Key = loadId ensures per-load partition ordering,
 * which is the foundation of the write-flattening pattern.
 */
@Component
public class BidEventProducer {

    private static final Logger log = LoggerFactory.getLogger(BidEventProducer.class);

    private final KafkaTemplate<String, BidEvent> kafkaTemplate;
    private final String topic;

    public BidEventProducer(
            KafkaTemplate<String, BidEvent> kafkaTemplate,
            @Value("${bid.topic:bid-events}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(BidEvent event) {
        String key = String.valueOf(event.loadId());
        kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish bid event bidId={} loadId={}: {}",
                                event.bidId(), event.loadId(), ex.getMessage(), ex);
                    } else {
                        log.debug("Published bid event bidId={} loadId={} to partition={} offset={}",
                                event.bidId(), event.loadId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
