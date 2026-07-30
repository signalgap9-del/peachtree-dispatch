package com.freightscaler.loadboard.producer;

import com.freightscaler.loadboard.model.LoadEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class LoadEventProducer {

    private static final Logger log = LoggerFactory.getLogger(LoadEventProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public LoadEventProducer(KafkaTemplate<String, Object> kafkaTemplate,
                             @Value("${loadboard.topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(LoadEvent event) {
        String key = String.valueOf(event.loadId());
        kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish load event {} for load {}",
                                event.type(), event.loadId(), ex);
                    } else {
                        log.info("Published {} event for load {} to {} [partition={}, offset={}]",
                                event.type(), event.loadId(), topic,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
