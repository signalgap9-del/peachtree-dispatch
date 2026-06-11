package com.atmospath.platform.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import software.amazon.awssdk.services.lambda.LambdaClient;

@Configuration
public class WebConfig {
    @Bean
    RestClient riskEngineClient(
            RestClient.Builder builder,
            @Value("${atmospath.risk-engine-url}") String riskEngineUrl) {
        return builder.baseUrl(riskEngineUrl).build();
    }

    @Bean
    @ConditionalOnProperty(name = "atmospath.risk-engine-mode", havingValue = "lambda")
    LambdaClient lambdaClient() {
        return LambdaClient.create();
    }

    @Bean
    WebMvcConfigurer corsConfigurer(
            @Value("${atmospath.cors-origins}") List<String> origins) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(origins.toArray(String[]::new))
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
            }
        };
    }
}
