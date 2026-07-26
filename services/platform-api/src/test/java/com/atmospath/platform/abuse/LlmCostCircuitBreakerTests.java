package com.atmospath.platform.abuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class LlmCostCircuitBreakerTests {

    private static final Instant NOW = Instant.parse("2026-07-27T14:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private AbuseGuardProperties properties;
    private LlmCostCircuitBreaker breaker;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        // Cap at $5/day, probe interval 60s
        properties = new AbuseGuardProperties(
                true, 5.0, 0.00014, 0.00028, 4000, 20, 0, 60_000L);

        breaker = new LlmCostCircuitBreaker(redis, properties, FIXED_CLOCK);
    }

    @Test
    void underCapAllowsRequest() {
        // Redis returns $1.00 worth of micros (1_000_000)
        when(valueOps.get(anyString())).thenReturn("1000000");

        LlmCostCircuitBreaker.Decision decision = breaker.checkRequest(100);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.state()).isEqualTo(LlmCostCircuitBreaker.State.CLOSED);
    }

    @Test
    void zeroCostAllowsRequest() {
        when(valueOps.get(anyString())).thenReturn(null);

        LlmCostCircuitBreaker.Decision decision = breaker.checkRequest(500);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.state()).isEqualTo(LlmCostCircuitBreaker.State.CLOSED);
    }

    @Test
    void overCapOpensCircuitAndRejects() {
        // Redis returns $6.00 (over the $5 cap)
        when(valueOps.get(anyString())).thenReturn("6000000");

        // First call after cap: the probe interval has elapsed (lastProbe=0),
        // so the first request gets through as a half-open probe.
        LlmCostCircuitBreaker.Decision first = breaker.checkRequest(100);
        assertThat(first.allowed()).isTrue();
        assertThat(first.state()).isEqualTo(LlmCostCircuitBreaker.State.HALF_OPEN);

        // Second call within the probe interval: rejected
        LlmCostCircuitBreaker.Decision second = breaker.checkRequest(100);
        assertThat(second.allowed()).isFalse();
        assertThat(second.state()).isEqualTo(LlmCostCircuitBreaker.State.OPEN);
        assertThat(second.reason()).contains("temporarily paused");
    }

    @Test
    void halfOpenProbeAllowedAfterCooldown() {
        when(valueOps.get(anyString())).thenReturn("6000000");

        // Use a mutable clock to simulate time passing
        var mutableClock = new MutableClock(NOW);
        var timedBreaker = new LlmCostCircuitBreaker(redis, properties, mutableClock);

        // First probe
        LlmCostCircuitBreaker.Decision first = timedBreaker.checkRequest(100);
        assertThat(first.allowed()).isTrue();
        assertThat(first.state()).isEqualTo(LlmCostCircuitBreaker.State.HALF_OPEN);

        // Immediately after: rejected (within probe interval)
        LlmCostCircuitBreaker.Decision second = timedBreaker.checkRequest(100);
        assertThat(second.allowed()).isFalse();

        // Advance past the probe interval (60s)
        mutableClock.advance(61_000L);

        // Another probe should be allowed
        LlmCostCircuitBreaker.Decision third = timedBreaker.checkRequest(100);
        assertThat(third.allowed()).isTrue();
        assertThat(third.state()).isEqualTo(LlmCostCircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void recordCostIncrementsRedis() {
        when(valueOps.increment(anyString(), anyLong())).thenReturn(196L);
        when(redis.expire(anyString(), any())).thenReturn(true);

        // 1000 input tokens at $0.00014/1k = $0.00014 = 140 micros
        // 200 output tokens at $0.00028/1k = $0.000056 = 56 micros
        // Total = 196 micros
        breaker.recordCost(1000, 200);

        // Verify the increment was called (cost stored as micro-USD)
        org.mockito.Mockito.verify(valueOps).increment(
                org.mockito.ArgumentMatchers.eq(LlmCostCircuitBreaker.key(LocalDate.of(2026, 7, 27))),
                org.mockito.ArgumentMatchers.eq(196L));
    }

    @Test
    void redisFailureFallsBackToInMemory() {
        // Redis fails on read
        when(valueOps.get(anyString()))
                .thenThrow(new RedisConnectionFailureException("Connection refused"));

        // Should still allow (in-memory starts at 0)
        LlmCostCircuitBreaker.Decision decision = breaker.checkRequest(100);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.state()).isEqualTo(LlmCostCircuitBreaker.State.CLOSED);
    }

    @Test
    void redisFailureOnRecordUsesInMemoryFallback() {
        // Redis fails on write
        when(valueOps.increment(anyString(), anyLong()))
                .thenThrow(new RedisConnectionFailureException("Connection refused"));
        // Redis also fails on read
        when(valueOps.get(anyString()))
                .thenThrow(new RedisConnectionFailureException("Connection refused"));

        // Record a large cost: 10_000_000 input tokens + 10_000_000 output tokens
        // Cost = (10000 * 0.00014) + (10000 * 0.00028) = 1.4 + 2.8 = $4.20
        breaker.recordCost(10_000_000, 10_000_000);

        // Record more to push over $5: another $1.40
        breaker.recordCost(10_000_000, 0);

        // Now check: in-memory should have ~$5.60, over the $5 cap
        LlmCostCircuitBreaker.Decision decision = breaker.checkRequest(100);
        // First call is a half-open probe (lastProbe=0, interval elapsed)
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.state()).isEqualTo(LlmCostCircuitBreaker.State.HALF_OPEN);

        // Second call: rejected
        LlmCostCircuitBreaker.Decision second = breaker.checkRequest(100);
        assertThat(second.allowed()).isFalse();
        assertThat(second.state()).isEqualTo(LlmCostCircuitBreaker.State.OPEN);
    }

    @Test
    void currentStateReflectsCost() {
        when(valueOps.get(anyString())).thenReturn("1000000"); // $1.00
        assertThat(breaker.currentState()).isEqualTo(LlmCostCircuitBreaker.State.CLOSED);

        when(valueOps.get(anyString())).thenReturn("6000000"); // $6.00
        // HALF_OPEN because probe interval has elapsed (lastProbe=0)
        assertThat(breaker.currentState()).isEqualTo(LlmCostCircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void keyIncludesDate() {
        String key = LlmCostCircuitBreaker.key(LocalDate.of(2026, 7, 27));
        assertThat(key).isEqualTo("abuse:llm-cost:2026-07-27");
    }

    /** Simple mutable clock for testing time-dependent behavior. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(long millis) {
            now = now.plusMillis(millis);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
