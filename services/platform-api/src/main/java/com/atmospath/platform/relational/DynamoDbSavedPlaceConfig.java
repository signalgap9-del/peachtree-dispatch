package com.atmospath.platform.relational;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Configuration
public class DynamoDbSavedPlaceConfig {
    @Bean
    @ConditionalOnMissingBean(DynamoDbClient.class)
    @ConditionalOnExpression("'${atmospath.saved-place-store:none}' == 'dynamodb' || '${atmospath.usage-store:memory}' == 'dynamodb' || '${atmospath.idempotency-store:memory}' == 'dynamodb'")
    DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.create();
    }
}
