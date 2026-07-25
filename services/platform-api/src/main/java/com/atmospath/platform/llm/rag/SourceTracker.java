package com.atmospath.platform.llm.rag;

import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Tracks which historical sources influenced an LLM response by scanning
 * the generated text for observation IDs, dates, and route references.
 * Produces a citation footer for display in the UI.
 */
@Service
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
public class SourceTracker {

    private static final Logger log = LoggerFactory.getLogger(SourceTracker.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Scans the LLM response for references to the provided sources and
     * returns a {@link TrackedResponse} with cited sources and a formatted
     * citation footer.
     */
    public TrackedResponse trackSources(String llmResponse, List<SourceReference> sources) {
        if (llmResponse == null || llmResponse.isBlank() || sources.isEmpty()) {
            return new TrackedResponse(llmResponse != null ? llmResponse : "", List.of(), "");
        }

        String lowerResponse = llmResponse.toLowerCase(Locale.ROOT);

        List<SourceReference> cited = sources.stream()
                .filter(source -> isCited(lowerResponse, source))
                .toList();

        String footer = cited.isEmpty() ? "" : buildCitationFooter(cited);

        log.debug("Source tracking: {}/{} sources cited in response", cited.size(), sources.size());
        return new TrackedResponse(llmResponse, cited, footer);
    }

    /**
     * Builds a citation footer listing all cited sources with date, route,
     * risk score, and a short observation ID reference.
     *
     * <p>Format:
     * <pre>
     * ---
     * 근거:
     * - [2026-03-15] I-95 South, risk 85/100 (관측 #abc123)
     * </pre>
     */
    public String buildCitationFooter(List<SourceReference> citedSources) {
        if (citedSources.isEmpty()) {
            return "";
        }

        String citations = citedSources.stream()
                .map(this::formatCitation)
                .collect(Collectors.joining("\n"));

        return "\n\n---\n근거:\n" + citations;
    }

    // ---- Internals ----

    /**
     * Checks whether a source is referenced in the response text. Matches on:
     * <ul>
     *   <li>Observation ID (full or first 8 chars)</li>
     *   <li>Observation date (ISO format)</li>
     *   <li>Route ID combined with date</li>
     * </ul>
     */
    private boolean isCited(String lowerResponse, SourceReference source) {
        // Check observation ID (full UUID or short form)
        String fullId = source.observationId().toString().toLowerCase(Locale.ROOT);
        String shortId = fullId.substring(0, Math.min(8, fullId.length()));
        if (lowerResponse.contains(fullId) || lowerResponse.contains(shortId)) {
            return true;
        }

        // Check observation date
        String date = DATE_FMT.format(source.observationTime().atZone(ZoneOffset.UTC));
        if (lowerResponse.contains(date)) {
            return true;
        }

        // Check route ID + risk score combination (e.g., "I-95" + "85")
        String routeDisplay = source.routeDisplay();
        if (!"unknown".equals(routeDisplay)) {
            String routeLower = routeDisplay.toLowerCase(Locale.ROOT);
            String riskStr = String.valueOf(source.riskScore());
            if (lowerResponse.contains(routeLower) && lowerResponse.contains(riskStr)) {
                return true;
            }
        }

        return false;
    }

    private String formatCitation(SourceReference source) {
        String date = DATE_FMT.format(source.observationTime().atZone(ZoneOffset.UTC));
        String shortId = source.observationId().toString().substring(0,
                Math.min(8, source.observationId().toString().length()));
        return String.format("- [%s] %s, risk %d/100 (관측 #%s)",
                date, source.routeDisplay(), source.riskScore(), shortId);
    }
}
