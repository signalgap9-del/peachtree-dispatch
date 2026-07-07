package com.atmospath.platform.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "atmospath.rate-limit.store", havingValue = "redis")
public class RedisRateLimitRepository implements RateLimitRepository {
    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisRateLimitRepository(StringRedisTemplate redis) {
        this(redis, Clock.systemUTC());
    }

    RedisRateLimitRepository(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public RateLimitDecision consume(String key, int limit, Duration window) {
        var nowMs = clock.millis();
        var windowMs = window.toMillis();
        var windowStartMs = nowMs - (nowMs % windowMs);
        var resetEpochSecond = (windowStartMs + windowMs) / 1000;
        var redisKey = "atmospath:rate-limit:" + hash(key) + ":" + windowStartMs;
        var count = redis.opsForValue().increment(redisKey);
        if (count != null && count == 1L) {
            redis.expire(redisKey, window.plusSeconds(5));
        }
        var safeCount = count == null ? limit + 1L : count;
        var remaining = Math.max(0, limit - safeCount);
        return new RateLimitDecision(safeCount <= limit, limit, (int) remaining, resetEpochSecond);
    }

    private static String hash(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash rate-limit key", exception);
        }
    }
}
