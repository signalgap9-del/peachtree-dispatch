package com.atmospath.platform.saas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.atmospath.platform.saas.redis.RedisQuotaCounter;
import com.atmospath.platform.saas.repository.EntitlementRepository;
import com.atmospath.platform.saas.repository.UsageRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

class QuotaServiceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 27);
    private static final Clock FIXED = Clock.fixed(
            TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private RedisQuotaCounter quotaCounter;
    private StringRedisTemplate redis;
    private EntitlementRepository entitlementRepo;
    private UsageRecordRepository usageRecordRepo;
    private QuotaService service;

    @BeforeEach
    void setUp() {
        quotaCounter = mock(RedisQuotaCounter.class);
        redis = mock(StringRedisTemplate.class);
        entitlementRepo = mock(EntitlementRepository.class);
        usageRecordRepo = mock(UsageRecordRepository.class);
        service = new QuotaService(quotaCounter, redis, entitlementRepo, usageRecordRepo, FIXED);
    }

    @Test
    void consumeWithinLimitReturnsUsage() {
        when(entitlementRepo.findActiveQuotaLimit(TENANT, "route_plan")).thenReturn(Optional.of(100));
        when(quotaCounter.increment(TENANT, "route_plan", TODAY)).thenReturn(5L);

        QuotaUsage usage = service.consume(TENANT, "route_plan");

        assertThat(usage.used()).isEqualTo(5);
        assertThat(usage.limit()).isEqualTo(100);
        assertThat(usage.remaining()).isEqualTo(95);
        assertThat(usage.exhausted()).isFalse();
    }

    @Test
    void consumeOverLimitThrowsAndDecrements() {
        when(entitlementRepo.findActiveQuotaLimit(TENANT, "route_plan")).thenReturn(Optional.of(10));
        when(quotaCounter.increment(TENANT, "route_plan", TODAY)).thenReturn(11L);

        assertThatThrownBy(() -> service.consume(TENANT, "route_plan"))
                .isInstanceOf(QuotaLimitExceededException.class);
        verify(quotaCounter).decrement(TENANT, "route_plan", TODAY);
    }

    @Test
    void consumeWithZeroLimitMeansUnlimited() {
        when(entitlementRepo.findActiveQuotaLimit(TENANT, "route_plan")).thenReturn(Optional.empty());
        when(quotaCounter.increment(TENANT, "route_plan", TODAY)).thenReturn(999L);

        QuotaUsage usage = service.consume(TENANT, "route_plan");

        assertThat(usage.used()).isEqualTo(999);
        assertThat(usage.limit()).isZero();
    }

    @Test
    void consumeFallsBackToDatabaseWhenRedisDown() {
        when(entitlementRepo.findActiveQuotaLimit(TENANT, "route_plan")).thenReturn(Optional.of(100));
        when(quotaCounter.increment(TENANT, "route_plan", TODAY))
                .thenThrow(new DataAccessResourceFailureException("Redis down"));
        when(usageRecordRepo.checkAndConsumeQuota(TENANT, "route_plan")).thenReturn(true);
        when(usageRecordRepo.sumForDate(TENANT, "route_plan", TODAY)).thenReturn(3L);

        QuotaUsage usage = service.consume(TENANT, "route_plan");

        assertThat(usage.used()).isEqualTo(3);
    }

    @Test
    void consumeDatabaseFallbackThrowsWhenOverLimit() {
        when(entitlementRepo.findActiveQuotaLimit(TENANT, "route_plan")).thenReturn(Optional.of(10));
        when(quotaCounter.increment(TENANT, "route_plan", TODAY))
                .thenThrow(new DataAccessResourceFailureException("Redis down"));
        when(usageRecordRepo.checkAndConsumeQuota(TENANT, "route_plan")).thenReturn(false);

        assertThatThrownBy(() -> service.consume(TENANT, "route_plan"))
                .isInstanceOf(QuotaLimitExceededException.class);
    }

    @Test
    void getUsageReadsFromRedisFirst() {
        when(entitlementRepo.findActiveQuotaLimit(TENANT, "route_plan")).thenReturn(Optional.of(50));
        when(quotaCounter.current(TENANT, "route_plan", TODAY)).thenReturn(Optional.of(7L));

        QuotaUsage usage = service.getUsage(TENANT, "route_plan");

        assertThat(usage.used()).isEqualTo(7);
        assertThat(usage.limit()).isEqualTo(50);
        verify(usageRecordRepo, never()).sumForDate(any(), any(), any());
    }

    @Test
    void getUsageFallsBackToDatabaseWhenRedisCounterExpired() {
        when(entitlementRepo.findActiveQuotaLimit(TENANT, "route_plan")).thenReturn(Optional.of(50));
        when(quotaCounter.current(TENANT, "route_plan", TODAY)).thenReturn(Optional.empty());
        when(usageRecordRepo.sumForDate(TENANT, "route_plan", TODAY)).thenReturn(12L);

        QuotaUsage usage = service.getUsage(TENANT, "route_plan");

        assertThat(usage.used()).isEqualTo(12);
    }

    @Test
    void getUsageFallsBackToDatabaseWhenRedisDown() {
        when(entitlementRepo.findActiveQuotaLimit(TENANT, "route_plan")).thenReturn(Optional.of(50));
        when(quotaCounter.current(TENANT, "route_plan", TODAY))
                .thenThrow(new DataAccessResourceFailureException("Redis down"));
        when(usageRecordRepo.sumForDate(TENANT, "route_plan", TODAY)).thenReturn(4L);

        QuotaUsage usage = service.getUsage(TENANT, "route_plan");

        assertThat(usage.used()).isEqualTo(4);
    }
}
