package com.atmospath.platform.saas.cdc;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Container factory for the Debezium CDC listener. Failed records are
 * retried three times, then published to the dead-letter topic
 * ({@code atmospath.public.alert_event.DLT}) so the consumer offset can
 * always advance.
 */
@Configuration
@ConditionalOnProperty(name = {"atmospath.saas.enabled", "atmospath.cdc.enabled"}, havingValue = "true")
public class CdcKafkaConfig {

    public static final String CONTAINER_FACTORY = "cdcContainerFactory";

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> cdcContainerFactory(
            @Value("${atmospath.cdc.bootstrap-servers:localhost:9092}") String bootstrapServers,
            KafkaTemplate<Object, Object> kafkaTemplate) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "platform-api-cdc");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
        factory.setCommonErrorHandler(cdcErrorHandler(kafkaTemplate));
        return factory;
    }

    private CommonErrorHandler cdcErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(kafkaTemplate), new FixedBackOff(2_000L, 3));
    }
}
