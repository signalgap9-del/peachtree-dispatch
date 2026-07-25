package com.atmospath.platform.saas.entity;

import java.io.Serializable;
import java.util.UUID;

/** Composite key {@code PRIMARY KEY (tenant_id, operation, key_hash)}. */
public record IdempotencyKeyId(UUID tenantId, String operation, String keyHash) implements Serializable {
}
