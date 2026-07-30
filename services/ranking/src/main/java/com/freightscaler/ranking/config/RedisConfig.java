package com.freightscaler.ranking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis wiring. All ranking keys, members, and scores are plain strings
 * (scores are managed by Redis sorted-set commands), so StringRedisTemplate
 * is the only template this service needs. The connection itself comes from
 * spring.data.redis.url (REDIS_URL).
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
