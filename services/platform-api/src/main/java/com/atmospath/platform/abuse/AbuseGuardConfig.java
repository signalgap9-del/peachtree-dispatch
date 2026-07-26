package com.atmospath.platform.abuse;

import java.time.Clock;

import com.atmospath.platform.ratelimit.RateLimitRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Wires the anti-abuse / financial protection layer. All beans are conditional
 * on {@code atmospath.abuse.enabled=true} (the default) so the layer can be
 * disabled in tests or local dev without side effects.
 */
@Configuration
@ConditionalOnProperty(name = "atmospath.abuse.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AbuseGuardProperties.class)
public class AbuseGuardConfig {

    @Bean
    LlmCostCircuitBreaker llmCostCircuitBreaker(StringRedisTemplate redis, AbuseGuardProperties properties) {
        return new LlmCostCircuitBreaker(redis, properties);
    }

    @Bean
    LlmAbuseGuardFilter llmAbuseGuardFilter(
            AbuseGuardProperties properties,
            LlmCostCircuitBreaker circuitBreaker,
            RateLimitRepository rateLimitRepository,
            ObjectMapper objectMapper) {
        return new LlmAbuseGuardFilter(properties, circuitBreaker, rateLimitRepository, objectMapper, Clock.systemUTC());
    }
}
