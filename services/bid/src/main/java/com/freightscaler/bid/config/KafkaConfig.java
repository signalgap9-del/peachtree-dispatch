package com.freightscaler.bid.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Value("${bid.topic:bid-events}")
    private String topicName;

    /**
     * Auto-create the bid-events topic with 6 partitions.
     * Partition count determines parallelism per loadId key.
     */
    @Bean
    public NewTopic bidEventsTopic() {
        return TopicBuilder.name(topicName)
                .partitions(6)
                .replicas(1)
                .build();
    }
}
