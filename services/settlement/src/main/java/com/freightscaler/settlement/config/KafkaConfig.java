package com.freightscaler.settlement.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Value("${settlement.topic:settlement-events}")
    private String topicName;

    /**
     * Auto-create the settlement-events topic with 6 partitions.
     * Partition key = settlementId ensures per-settlement ordering.
     */
    @Bean
    public NewTopic settlementEventsTopic() {
        return TopicBuilder.name(topicName)
                .partitions(6)
                .replicas(1)
                .build();
    }
}
