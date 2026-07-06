package com.atmospath.platform.account;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "atmospath.usage-store", havingValue = "memory", matchIfMissing = true)
public class InMemoryUsageRepository implements UsageRepository {
    private final ConcurrentHashMap<UsageKey, AtomicInteger> counters = new ConcurrentHashMap<>();

    @Override
    public int incrementAndGet(UUID tenantId, MeteredFeature feature, LocalDate day) {
        return counters.computeIfAbsent(new UsageKey(tenantId, feature, day), ignored -> new AtomicInteger())
                .incrementAndGet();
    }

    @Override
    public int current(UUID tenantId, MeteredFeature feature, LocalDate day) {
        var counter = counters.get(new UsageKey(tenantId, feature, day));
        return counter == null ? 0 : counter.get();
    }

    private record UsageKey(UUID tenantId, MeteredFeature feature, LocalDate day) {
        private UsageKey {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(feature, "feature");
            Objects.requireNonNull(day, "day");
        }
    }
}
