package com.atmospath.platform.abuse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Financial circuit breaker that tracks estimated daily LLM cost and opens
 * (rejects requests) when the configured USD cap is exceeded.
 *
 * <p>States: CLOSED (requests flow), OPEN (cap exceeded, reject all),
 * HALF_OPEN (after cooldown, one probe request allowed to test reset).
 *
 * <p>The daily cost counter lives in Redis (key includes the UTC date, TTL at
 * end-of-day). When Redis is unreachable an in-memory fallback keeps the guard
 * functional within a single JVM instance.
 */
public class LlmCostCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    public record Decision(boolean allowed, State state, String reason) {
        static Decision allow(State state) {
            return new Decision(true, state, "");
        }

        static Decision deny(State state, String reason) {
            return new Decision(false, state, reason);
        }
    }

    static final String KEY_PREFIX = "abuse:llm-cost";
    private static final Logger log = LoggerFactory.getLogger(LlmCostCircuitBreaker.class);
    private static final Duration EXPIRY_BUFFER = Duration.ofMinutes(30);
    private static final int CHARS_PER_TOKEN = 4;

    private final StringRedisTemplate redis;
    private final AbuseGuardProperties properties;
    private final Clock clock;

    // In-memory fallback state
    private final AtomicLong fallbackCostMicros = new AtomicLong(0);
    private final AtomicReference<LocalDate> fallbackDate = new AtomicReference<>(LocalDate.now(ZoneOffset.UTC));

    // Half-open probe tracking
    private final AtomicLong lastProbeEpochMs = new AtomicLong(0);

    public LlmCostCircuitBreaker(StringRedisTemplate redis, AbuseGuardProperties properties) {
        this(redis, properties, Clock.systemUTC());
    }

    LlmCostCircuitBreaker(StringRedisTemplate redis, AbuseGuardProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Checks whether a new LLM request with the given estimated input size
     * may proceed. Does NOT record cost; call {@link #recordCost} after the
     * LLM call completes.
     */
    public Decision checkRequest(int estimatedInputChars) {
        LocalDate today = today();
        double currentCostUsd = getCurrentCostUsd(today);
        double capUsd = properties.llmDailyCostCapUsd();

        if (currentCostUsd < capUsd) {
            return Decision.allow(State.CLOSED);
        }

        // Over cap: check if we should allow a half-open probe
        long now = clock.millis();
        long lastProbe = lastProbeEpochMs.get();
        if (now - lastProbe >= properties.halfOpenProbeIntervalMs()) {
            if (lastProbeEpochMs.compareAndSet(lastProbe, now)) {
                log.info("LLM cost circuit breaker HALF_OPEN: allowing probe request (cost=${}, cap=${})",
                        String.format("%.4f", currentCostUsd), String.format("%.2f", capUsd));
                return Decision.allow(State.HALF_OPEN);
            }
        }

        return Decision.deny(State.OPEN,
                "AI features temporarily paused: daily cost cap reached. Resets at midnight UTC.");
    }

    /**
     * Records the estimated cost of a completed LLM call.
     *
     * @param inputTokens  estimated input tokens consumed
     * @param outputTokens estimated output tokens generated
     */
    public void recordCost(int inputTokens, int outputTokens) {
        double costUsd = properties.estimateCostUsd(inputTokens, outputTokens);
        if (costUsd <= 0) {
            return;
        }
        // Store as micro-USD (long) to avoid floating-point drift in Redis INCRBYFLOAT
        long costMicros = Math.round(costUsd * 1_000_000);
        LocalDate today = today();

        try {
            String key = key(today);
            Long result = redis.opsForValue().increment(key, costMicros);
            if (result != null && result == costMicros) {
                redis.expire(key, timeUntilEndOfDay(today));
            }
        } catch (Exception ex) {
            log.warn("Redis unavailable for cost recording; using in-memory fallback: {}", ex.toString());
            recordFallback(costMicros, today);
        }
    }

    /**
     * Convenience: estimate tokens from character count and record.
     */
    public void recordCostFromChars(int inputChars, int outputChars) {
        int inputTokens = Math.max(1, inputChars / CHARS_PER_TOKEN);
        int outputTokens = Math.max(1, outputChars / CHARS_PER_TOKEN);
        recordCost(inputTokens, outputTokens);
    }

    /** Current state for observability / status endpoints. */
    public State currentState() {
        double cost = getCurrentCostUsd(today());
        if (cost < properties.llmDailyCostCapUsd()) {
            return State.CLOSED;
        }
        long now = clock.millis();
        if (now - lastProbeEpochMs.get() >= properties.halfOpenProbeIntervalMs()) {
            return State.HALF_OPEN;
        }
        return State.OPEN;
    }

    /** Current estimated daily cost in USD. */
    public double getCurrentCostUsd(LocalDate date) {
        try {
            String key = key(date);
            String value = redis.opsForValue().get(key);
            if (value != null) {
                return Long.parseLong(value) / 1_000_000.0;
            }
            return 0.0;
        } catch (Exception ex) {
            log.warn("Redis unavailable for cost read; using in-memory fallback: {}", ex.toString());
            return getFallbackCostUsd(date);
        }
    }

    // --- In-memory fallback ---

    private void recordFallback(long costMicros, LocalDate today) {
        resetFallbackIfNewDay(today);
        fallbackCostMicros.addAndGet(costMicros);
    }

    private double getFallbackCostUsd(LocalDate today) {
        resetFallbackIfNewDay(today);
        return fallbackCostMicros.get() / 1_000_000.0;
    }

    private void resetFallbackIfNewDay(LocalDate today) {
        LocalDate stored = fallbackDate.get();
        if (!today.equals(stored)) {
            if (fallbackDate.compareAndSet(stored, today)) {
                fallbackCostMicros.set(0);
            }
        }
    }

    // --- Helpers ---

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    static String key(LocalDate date) {
        return KEY_PREFIX + ":" + date;
    }

    private Duration timeUntilEndOfDay(LocalDate date) {
        Instant endOfDay = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return Duration.between(clock.instant(), endOfDay).plus(EXPIRY_BUFFER);
    }
}
