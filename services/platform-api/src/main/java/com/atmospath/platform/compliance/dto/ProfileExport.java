package com.atmospath.platform.compliance.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * GDPR Article 15/20 export of the member profile row. Null when no member
 * row exists for the authenticated identity.
 */
public record ProfileExport(
        UUID memberId,
        UUID tenantId,
        String email,
        String displayName,
        String role,
        Instant createdAt) {
}
