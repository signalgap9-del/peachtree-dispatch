package com.atmospath.platform.saas.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

import com.atmospath.platform.saas.redis.RedisQuotaCounter;
import com.atmospath.platform.saas.repository.EntitlementRepository;
import com.atmospath.platform.saas.repository.UsageRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.Cursor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dual-ledger quota enforcement (ADR 0011). The hot path increments a Redis
 * counter and compares it against the entitlement limit; a scheduled
 * reconcile job copies Redis counters into the partitioned
 * {@code usage_record} table so Postgres remains the durable ledger. When
 * Redis is unreachable, the {@code check_and_consume_quota()} database
 * function takes over.
 */
@Service
@ConditionalOnProperty(name = "atmospath.saas.enabled", havingValue = "true")
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private final RedisQuotaCounter quotaCounter;
    private final StringRedisTemplate redis;
    private final EntitlementRepository entitlementRepository;
    private final UsageRecordRepository usageRecordRepository;
    private final Clock clock;

    public QuotaService(RedisQuotaCounter quotaCounter,
                        StringRedisTemplate redis,
                        EntitlementRepository entitlementRepository,
                        UsageRecordRepository usageRecordRepository) {
        this(quotaCounter, redis, entitlementRepository, usageRecordRepository, Clock.systemUTC());
    }

    QuotaService(RedisQuotaCounter quotaCounter,
                 StringRedisTemplate redis,
                 EntitlementRepository entitlementRepository,
                 UsageRecordRepository usageRecordRepository,
                 Clock clock) {
        this.quotaCounter = quotaCounter;
        this.redis = redis;
        this.entitlementRepository = entitlementRepository;
        this.usageRecordRepository = usageRecordRepository;
        this.clock = clock;
    }

    /**
     * Consumes one unit of a daily feature quota.
     *
     * @throws QuotaLimitExceededException when the tenant is over its limit
     */
    public QuotaUsage consume(UUID tenantId, String feature) {
        LocalDate today = today();
        long limit = activeLimit(tenantId, feature);
        try {
            long used = quotaCounter.increment(tenantId, feature, today);
            if (limit > 0 && used > limit) {
                quotaCounter.decrement(tenantId, feature, today);
                throw new QuotaLimitExceededException(tenantId, feature, limit);
            }
            return new QuotaUsage(tenantId, feature, used, limit, today);
        } catch (DataAccessResourceFailureException ex) {
            log.warn("Redis unavailable for quota check; falling back to database ledger: {}", ex.toString());
            return consumeFromDatabase(tenantId, feature, today, limit);
        }
    }

    /** Fast read: Redis first, database when the counter has expired or Redis is down. */
    @Transactional(value = "saasTransactionManager", readOnly = true)
    public QuotaUsage getUsage(UUID tenantId, String feature) {
        LocalDate today = today();
        long limit = activeLimit(tenantId, feature);
        try {
            long used = quotaCounter.current(tenantId, feature, today)
                    .orElseGet(() -> usageRecordRepository.sumForDate(tenantId, feature, today));
            return new QuotaUsage(tenantId, feature, used, limit, today);
        } catch (DataAccessResourceFailureException ex) {
            log.warn("Redis unavailable for quota read; reading database ledger: {}", ex.toString());
            long used = usageRecordRepository.sumForDate(tenantId, feature, today);
            return new QuotaUsage(tenantId, feature, used, limit, today);
        }
    }

    /**
     * Syncs live Redis counters into the partitioned usage table. The
     * counter value replaces the day's rows so repeated runs converge to
     * the same state.
     */
    @Scheduled(fixedDelay = 300_000L, initialDelay = 60_000L)
    @Transactional("saasTransactionManager")
    public void reconcile() {
        int[] synced = {0};
        try {
            redis.execute((RedisConnection connection) -> {
                ScanOptions options = ScanOptions.scanOptions()
                        .match(RedisQuotaCounter.KEY_PREFIX + ":*")
                        .count(500)
                        .build();
                try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                    while (cursor.hasNext()) {
                        String key = new String(cursor.next(), StandardCharsets.UTF_8);
                        if (syncKey(key)) {
                            synced[0]++;
                        }
                    }
                }
                return null;
            });
        } catch (DataAccessResourceFailureException ex) {
            log.warn("Skipping quota reconcile; Redis unavailable: {}", ex.toString());
            return;
        }
        if (synced[0] > 0) {
            log.info("Quota reconcile synced {} counter(s) to usage_record", synced[0]);
        }
    }

    private boolean syncKey(String key) {
        // quota:{tenantId}:{feature}:{yyyy-MM-dd}
        String[] parts = key.split(":");
        if (parts.length < 4) {
            log.warn("Skipping malformed quota key: {}", key);
            return false;
        }
        try {
            UUID tenantId = UUID.fromString(parts[1]);
            LocalDate date = LocalDate.parse(parts[parts.length - 1]);
            String feature = String.join(":", Arrays.copyOfRange(parts, 2, parts.length - 1));
            String value = redis.opsForValue().get(key);
            if (value == null) {
                return false;
            }
            int count = Integer.parseInt(value);
            usageRecordRepository.deleteForDate(tenantId, feature, date);
            usageRecordRepository.insertCount(tenantId, feature, date, count);
            return true;
        } catch (IllegalArgumentException ex) {
            log.warn("Skipping unparseable quota key {}: {}", key, ex.toString());
            return false;
        }
    }

    private QuotaUsage consumeFromDatabase(UUID tenantId, String feature, LocalDate today, long limit) {
        boolean allowed = usageRecordRepository.checkAndConsumeQuota(tenantId, feature);
        if (!allowed) {
            throw new QuotaLimitExceededException(tenantId, feature, limit);
        }
        long used = usageRecordRepository.sumForDate(tenantId, feature, today);
        return new QuotaUsage(tenantId, feature, used, limit, today);
    }

    /** quota_limit = 0 means unlimited per the schema convention. */
    private long activeLimit(UUID tenantId, String feature) {
        return entitlementRepository.findActiveQuotaLimit(tenantId, feature)
                .map(Integer::longValue)
                .orElse(0L);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }
}
