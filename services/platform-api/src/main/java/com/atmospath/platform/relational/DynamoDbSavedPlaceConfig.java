package com.atmospath.platform.relational;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Configuration
public class DynamoDbSavedPlaceConfig {
    @Bean
    @ConditionalOnProperty(name = "atmospath.saved-place-store", havingValue = "dynamodb")
    DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.create();
    }
}
