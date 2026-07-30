package com.freightscaler.telemetry.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Redis-based rate limiter: max 1 ping per truck per 10 seconds.
 * Uses SET NX with a 10s TTL. Fails open if Redis is unavailable
 * so that ingestion is never blocked by a cache outage.
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private static final String KEY_PREFIX = "telemetry:rl:";
    private static final Duration WINDOW = Duration.ofSeconds(10);

    private final StringRedisTemplate redis;

    public RateLimitService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @return true if the ping is allowed, false if rate-limited.
     */
    public boolean isAllowed(UUID truckId) {
        try {
            String key = KEY_PREFIX + truckId;
            Boolean acquired = redis.opsForValue()
                    .setIfAbsent(key, "1", WINDOW);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.warn("Redis unavailable for rate-limit check on truck {}; failing open", truckId, e);
            return true;
        }
    }
}
