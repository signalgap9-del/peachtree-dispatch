package com.freightscaler.ranking.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.Map;

/**
 * Kafka wiring for the ranking consumer.
 *
 * <p>The listener multiplexes three topics owned by different producers
 * (bid, shipment, telemetry). Those producers publish JSON with per-service
 * {@code __TypeId__} headers whose classes are NOT on this service's classpath,
 * so header-driven deserialization would fail. We therefore override the
 * application.yml default (JsonDeserializer) with StringDeserializer and let
 * {@link com.freightscaler.ranking.consumer.RankingEventConsumer} parse each
 * payload by topic with Jackson ({@code ignoreUnknown=true}), which also keeps
 * ranking forward-compatible when producers add new fields.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            KafkaProperties kafkaProperties) {
        Map<String, Object> props = kafkaProperties.buildConsumerProperties(null);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
        factory.setConcurrency(3);
        return factory;
    }

    /**
     * shipment-events has no producer service yet; declaring the topic here makes
     * the ranking consumer's subscription deterministic instead of relying on
     * broker auto-creation. bid-events and telemetry-raw are owned and created by
     * their respective producer services.
     */
    @Bean
    public NewTopic shipmentEventsTopic() {
        return TopicBuilder.name("shipment-events")
                .partitions(6)
                .replicas(1)
                .build();
    }
}
