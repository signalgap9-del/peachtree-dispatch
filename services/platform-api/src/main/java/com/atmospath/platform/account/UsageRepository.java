package com.atmospath.platform.account;

import java.time.LocalDate;
import java.util.UUID;

public interface UsageRepository {
    int incrementAndGet(UUID tenantId, MeteredFeature feature, LocalDate day);

    int current(UUID tenantId, MeteredFeature feature, LocalDate day);
}
