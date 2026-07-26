package com.atmospath.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class LlmTokenBudgetServiceTests {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private LlmTokenBudgetService service;

    private static final LlmProperties PROPS = new LlmProperties(
            true, "http://localhost:4000", "key", "gpt-4o-mini",
            1024, 0.7, 60_000L, 100_000L, 4096);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new LlmTokenBudgetService(redis, PROPS);
    }

    @Test
    void canConsumeReturnsTrueWhenUnderBudget() {
        when(valueOps.get(anyString())).thenReturn("50000");

        assertThat(service.canConsume("tenant-1", 1000)).isTrue();
    }

    @Test
    void canConsumeReturnsFalseWhenOverBudget() {
        when(valueOps.get(anyString())).thenReturn("99500");

        assertThat(service.canConsume("tenant-1", 1000)).isFalse();
    }

    @Test
    void canConsumeReturnsTrueWhenNoUsageRecorded() {
        when(valueOps.get(anyString())).thenReturn(null);

        assertThat(service.canConsume("tenant-1", 5000)).isTrue();
    }

    @Test
    void canConsumeFailsOpenWhenRedisDown() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis down"));

        assertThat(service.canConsume("tenant-1", 5000)).isTrue();
    }

    @Test
    void recordUsageIncrementsCounter() {
        when(valueOps.increment(anyString(), eq(500L))).thenReturn(500L);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);

        service.recordUsage("tenant-1", 500);

        verify(valueOps).increment(anyString(), eq(500L));
        verify(redis).expire(anyString(), any(Duration.class));
    }

    @Test
    void recordUsageSkipsNonPositiveTokens() {
        service.recordUsage("tenant-1", 0);
        service.recordUsage("tenant-1", -10);

        verify(valueOps, never()).increment(anyString(), anyLong());
    }

    @Test
    void recordUsageSilentlySkipsWhenRedisDown() {
        when(valueOps.increment(anyString(), anyLong()))
                .thenThrow(new RuntimeException("Redis down"));

        // Should not throw
        service.recordUsage("tenant-1", 100);
    }

    @Test
    void getStatusReturnsCurrentSnapshot() {
        when(valueOps.get(anyString())).thenReturn("30000");

        LlmTokenBudgetService.TokenBudgetStatus status = service.getStatus("tenant-1");

        assertThat(status.usedToday()).isEqualTo(30000);
        assertThat(status.remaining()).isEqualTo(70000);
        assertThat(status.dailyBudget()).isEqualTo(100000);
    }

    @Test
    void getStatusReturnsFullBudgetWhenRedisDown() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis down"));

        LlmTokenBudgetService.TokenBudgetStatus status = service.getStatus("tenant-1");

        assertThat(status.usedToday()).isZero();
        assertThat(status.remaining()).isEqualTo(100000);
    }

    @Test
    void keyFormatIncludesTenantAndDate() {
        String key = LlmTokenBudgetService.key("t1", java.time.LocalDate.of(2026, 7, 27));
        assertThat(key).isEqualTo("llm:budget:t1:2026-07-27");
    }
}
