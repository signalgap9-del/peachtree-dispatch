package com.atmospath.platform.llm;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Per-tenant daily token budget enforcement backed by Redis.
 * Keys auto-expire shortly after midnight UTC (same pattern as
 * {@code RedisQuotaCounter}). If Redis is unavailable the service
 * fails open – budget tracking is a cost guard, not a security boundary.
 */
public class LlmTokenBudgetService {

    static final String KEY_PREFIX = "llm:budget";

    private static final Logger log = LoggerFactory.getLogger(LlmTokenBudgetService.class);
    private static final Duration EXPIRY_BUFFER = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;
    private final LlmProperties properties;

    public LlmTokenBudgetService(StringRedisTemplate redis, LlmProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public record TokenBudgetStatus(long usedToday, long remaining, long dailyBudget) {}

    /**
     * Returns {@code true} if the tenant may consume {@code estimatedTokens}
     * without exceeding the daily budget. Fails open when Redis is down.
     */
    public boolean canConsume(String tenantId, int estimatedTokens) {
        try {
            String key = key(tenantId, LocalDate.now(ZoneOffset.UTC));
            String value = redis.opsForValue().get(key);
            long used = value != null ? Long.parseLong(value) : 0L;
            return used + estimatedTokens <= properties.dailyTokenBudget();
        } catch (Exception ex) {
            log.warn("Redis unavailable for LLM budget check; failing open: {}", ex.toString());
            return true;
        }
    }

    /**
     * Atomically adds {@code tokens} to today's usage counter.
     * Silently skips recording when Redis is down.
     */
    public void recordUsage(String tenantId, int tokens) {
        if (tokens <= 0) {
            return;
        }
        try {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            String key = key(tenantId, today);
            Long used = redis.opsForValue().increment(key, tokens);
            if (used != null && used == (long) tokens) {
                redis.expire(key, timeUntilEndOfDay(today));
            }
        } catch (Exception ex) {
            log.warn("Redis unavailable for LLM usage recording: {}", ex.toString());
        }
    }

    /**
     * Current budget snapshot for the tenant. Returns the full budget
     * as remaining when Redis is unreachable.
     */
    public TokenBudgetStatus getStatus(String tenantId) {
        try {
            String key = key(tenantId, LocalDate.now(ZoneOffset.UTC));
            String value = redis.opsForValue().get(key);
            long used = value != null ? Long.parseLong(value) : 0L;
            long remaining = Math.max(0L, properties.dailyTokenBudget() - used);
            return new TokenBudgetStatus(used, remaining, properties.dailyTokenBudget());
        } catch (Exception ex) {
            log.warn("Redis unavailable for LLM budget status: {}", ex.toString());
            return new TokenBudgetStatus(0L, properties.dailyTokenBudget(), properties.dailyTokenBudget());
        }
    }

    static String key(String tenantId, LocalDate date) {
        return KEY_PREFIX + ":" + tenantId + ":" + date;
    }

    private static Duration timeUntilEndOfDay(LocalDate date) {
        Instant endOfDay = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return Duration.between(Instant.now(), endOfDay).plus(EXPIRY_BUFFER);
    }
}
