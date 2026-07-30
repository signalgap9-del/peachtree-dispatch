package com.freightscaler.settlement.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Prevents duplicate saga step execution using Redis SET NX with a 300-second TTL.
 * Key format: settlement:idem:{settlementId}:{step}
 * Fails open if Redis is unavailable so settlements are not blocked by cache outages.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final Duration IDEMPOTENCY_TTL = Duration.ofSeconds(300);
    private static final String KEY_PREFIX = "settlement:idem:";

    private final StringRedisTemplate redis;

    public IdempotencyService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @return true if this step execution is new (non-duplicate), false if duplicate
     */
    public boolean tryAcquire(Long settlementId, int step) {
        String key = KEY_PREFIX + settlementId + ":" + step;
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(key, "1", IDEMPOTENCY_TTL);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.warn("Redis unavailable for idempotency check, failing open: {}", e.getMessage());
            return true; // fail-open: allow the step through
        }
    }
}
