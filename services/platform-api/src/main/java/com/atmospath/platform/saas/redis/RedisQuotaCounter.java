package com.atmospath.platform.saas.redis;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Hot-path daily usage counter in Redis. Keys auto-expire shortly after
 * midnight UTC so stale counters never leak into the next day; the durable
 * copy lives in the partitioned {@code usage_record} table via reconcile.
 */
@Component
@ConditionalOnProperty(name = "atmospath.saas.enabled", havingValue = "true")
public class RedisQuotaCounter {

    public static final String KEY_PREFIX = "quota";

    private static final Logger log = LoggerFactory.getLogger(RedisQuotaCounter.class);
    private static final Duration EXPIRY_BUFFER = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;

    public RedisQuotaCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public static String key(UUID tenantId, String feature, LocalDate date) {
        return KEY_PREFIX + ":" + tenantId + ":" + feature + ":" + date;
    }

    /** Atomically increments today's counter and returns the new value. */
    public long increment(UUID tenantId, String feature, LocalDate date) {
        String redisKey = key(tenantId, feature, date);
        Long used = redis.opsForValue().increment(redisKey);
        if (used == null) {
            throw new IllegalStateException("Redis INCR returned no value for " + redisKey);
        }
        if (used == 1L) {
            redis.expire(redisKey, timeUntilEndOfDay(date));
        }
        return used;
    }

    /** Rolls back one increment after a rejected consumption (over-limit). */
    public void decrement(UUID tenantId, String feature, LocalDate date) {
        Long remaining = redis.opsForValue().decrement(key(tenantId, feature, date));
        if (remaining != null && remaining < 0L) {
            redis.opsForValue().set(key(tenantId, feature, date), "0", timeUntilEndOfDay(date));
        }
    }

    public Optional<Long> current(UUID tenantId, String feature, LocalDate date) {
        String value = redis.opsForValue().get(key(tenantId, feature, date));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(value));
        } catch (NumberFormatException ex) {
            log.warn("Corrupt quota counter at {}; treating as absent", key(tenantId, feature, date));
            return Optional.empty();
        }
    }

    private static Duration timeUntilEndOfDay(LocalDate date) {
        Instant endOfDay = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return Duration.between(Instant.now(), endOfDay).plus(EXPIRY_BUFFER);
    }
}
