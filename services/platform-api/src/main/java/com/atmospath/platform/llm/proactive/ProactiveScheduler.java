package com.atmospath.platform.llm.proactive;

import java.time.Instant;
import java.util.List;

import com.atmospath.platform.risk.RiskEngineGateway;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically re-evaluates monitored saved routes against the latest
 * national risk snapshot and publishes new suggestions when corridors
 * become risky. Disabled by default; enable with
 * {@code atmospath.llm.proactive.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "atmospath.llm.proactive.enabled", havingValue = "true", matchIfMissing = false)
public class ProactiveScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProactiveScheduler.class);

    private final ProactiveRiskAnalyzer analyzer;
    private final ProactiveSuggestionService suggestionService;
    private final RiskEngineGateway riskEngine;

    private volatile Instant lastCheckTime;
    private volatile int lastSuggestionCount;

    public ProactiveScheduler(ProactiveRiskAnalyzer analyzer,
                              ProactiveSuggestionService suggestionService,
                              RiskEngineGateway riskEngine) {
        this.analyzer = analyzer;
        this.suggestionService = suggestionService;
        this.riskEngine = riskEngine;
    }

    /**
     * Fetches the latest national risk snapshot and runs proactive
     * analysis on all monitored routes. New suggestions are published
     * to Redis and pushed to connected SSE clients.
     */
    @Scheduled(fixedDelayString = "${atmospath.llm.proactive.check-interval-ms:300000}")
    void checkMonitoredRoutes() {
        log.debug("Proactive check starting");
        Instant now = Instant.now();

        JsonNode snapshot;
        try {
            snapshot = riskEngine.get("/risk/national");
        } catch (RuntimeException ex) {
            log.warn("Risk engine unavailable; skipping proactive check: {}", ex.toString());
            return;
        }
        if (snapshot == null || snapshot.isMissingNode() || snapshot.isNull()) {
            log.warn("Risk engine returned empty national snapshot; skipping");
            return;
        }

        try {
            List<RiskSuggestion> suggestions = analyzer.analyzeAlertChange(snapshot);
            for (RiskSuggestion suggestion : suggestions) {
                suggestionService.publishSuggestion(suggestion);
            }
            lastSuggestionCount = suggestions.size();
            if (!suggestions.isEmpty()) {
                log.info("Proactive check produced {} suggestion(s)", suggestions.size());
            }
        } catch (RuntimeException ex) {
            log.error("Proactive analysis cycle failed: {}", ex.toString(), ex);
        } finally {
            lastCheckTime = now;
        }
    }

    /** Timestamp of the last completed (or attempted) check. */
    public Instant lastCheckTime() {
        return lastCheckTime;
    }

    /** Number of suggestions produced in the last check cycle. */
    public int lastSuggestionCount() {
        return lastSuggestionCount;
    }
}
