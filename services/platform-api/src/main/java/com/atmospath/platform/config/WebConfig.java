package com.atmospath.platform.config;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
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
        return LambdaClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(2))
                        .socketTimeout(Duration.ofSeconds(25)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(Duration.ofSeconds(25))
                        .apiCallTimeout(Duration.ofSeconds(27))
                        .build())
                .build();
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
