package com.atmospath.platform.relational;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.services.rdsdata.RdsDataClient;

@Configuration
@EnableConfigurationProperties(RelationalStoreProperties.class)
public class RelationalStoreConfig {
    @Bean
    @ConditionalOnProperty(name = "atmospath.relational.enabled", havingValue = "true")
    RdsDataClient rdsDataClient() {
        return RdsDataClient.create();
    }
}
