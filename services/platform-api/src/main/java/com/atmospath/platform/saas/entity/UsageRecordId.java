package com.atmospath.platform.saas.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Composite key of the range-partitioned usage_record table:
 * {@code PRIMARY KEY (tenant_id, feature, usage_date, id)}.
 */
public record UsageRecordId(UUID tenantId, String feature, LocalDate usageDate, UUID id) implements Serializable {
}
