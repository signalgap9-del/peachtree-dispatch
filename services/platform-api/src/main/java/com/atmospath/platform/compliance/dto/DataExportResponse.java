package com.atmospath.platform.compliance.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Full GDPR Article 20 portability payload for the authenticated user:
 * profile, saved routes, saved places, subscription history, and a usage
 * summary. API keys are deliberately excluded (hash-only, never
 * exportable, per docs/data-retention.md section 5).
 */
public record DataExportResponse(
        String subject,
        ProfileExport profile,
        List<SavedRouteExport> savedRoutes,
        List<SavedPlaceExport> savedPlaces,
        List<SubscriptionExport> subscriptions,
        Map<String, Integer> usageSummary,
        Instant exportedAt) {
}
