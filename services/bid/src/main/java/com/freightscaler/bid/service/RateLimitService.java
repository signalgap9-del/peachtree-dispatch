package com.freightscaler.bid.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Sliding-window rate limiter backed by Redis sorted sets.
 * Max N bids per carrier per 60-second window. Fails open if Redis is unavailable.
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private static final String KEY_PREFIX = "bid:rl:";
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final int maxBidsPerMinute;

    public RateLimitService(
            StringRedisTemplate redis,
            @Value("${bid.rate-limit.max-bids-per-carrier-per-minute:5}") int maxBidsPerMinute
    ) {
        this.redis = redis;
        this.maxBidsPerMinute = maxBidsPerMinute;
    }

    /**
     * @return true if the carrier is within rate limits, false if rate-limited
     */
    public boolean isAllowed(UUID carrierId) {
        String key = KEY_PREFIX + carrierId;
        try {
            long now = Instant.now().toEpochMilli();
            long windowStart = now - WINDOW.toMillis();

            // Remove entries outside the sliding window
            redis.opsForZSet().removeRangeByScore(key, 0, windowStart);

            // Count entries in the current window
            Long count = redis.opsForZSet().zCard(key);
            if (count != null && count >= maxBidsPerMinute) {
                return false;
            }

            // Add the current request with timestamp as score
            redis.opsForZSet().add(key, now + ":" + Math.random(), now);
            redis.expire(key, WINDOW);

            return true;
        } catch (Exception e) {
            log.warn("Redis unavailable for rate limit check, failing open: {}", e.getMessage());
            return true; // fail-open
        }
    }
}
