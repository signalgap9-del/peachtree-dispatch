package com.freightscaler.bid.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Prevents duplicate bids from the same carrier on the same load.
 * Uses Redis SET NX with a 60-second TTL. Fails open if Redis is unavailable.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final Duration IDEMPOTENCY_TTL = Duration.ofSeconds(60);
    private static final String KEY_PREFIX = "bid:idem:";

    private final StringRedisTemplate redis;

    public IdempotencyService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @return true if this is a new (non-duplicate) bid, false if duplicate
     */
    public boolean tryAcquire(UUID carrierId, Long loadId) {
        String key = KEY_PREFIX + carrierId + ":" + loadId;
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(key, "1", IDEMPOTENCY_TTL);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.warn("Redis unavailable for idempotency check, failing open: {}", e.getMessage());
            return true; // fail-open: allow the bid through
        }
    }
}
