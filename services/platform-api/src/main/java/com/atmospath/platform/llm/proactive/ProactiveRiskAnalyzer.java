package com.atmospath.platform.llm.proactive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.atmospath.platform.llm.LiteLlmClient;
import com.atmospath.platform.llm.Message;
import com.atmospath.platform.llm.prompt.PromptTemplateService;
import com.atmospath.platform.llm.rag.RagResult;
import com.atmospath.platform.llm.rag.RagService;
import com.atmospath.platform.llm.rag.SearchFilters;
import com.atmospath.platform.llm.security.ChatCompletion;
import com.atmospath.platform.llm.security.LlmFallbackChain;
import com.atmospath.platform.risk.RiskEngineGateway;
import com.atmospath.platform.saas.entity.SavedRouteEntity;
import com.atmospath.platform.saas.entity.TenantMember;
import com.atmospath.platform.saas.repository.SavedRouteRepository;
import com.atmospath.platform.saas.repository.TenantMemberRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Monitors alert changes and evaluates their impact on saved routes that
 * have monitoring enabled. When a route's corridor risk exceeds its
 * configured threshold, the analyzer uses RAG for historical context and
 * the LLM (with structured fallback) to generate an actionable suggestion.
 *
 * <p>Deduplication prevents the same route+alert combination from producing
 * repeated suggestions within a configurable window (default 1 hour).
 */
@Service
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
public class ProactiveRiskAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ProactiveRiskAnalyzer.class);
    private static final String PROMPT_TEMPLATE = "proactive_suggestion";
    private static final String DEDUP_PREFIX = "llm:proactive:dedup:";

    private final ObjectProvider<SavedRouteRepository> routeRepoProvider;
    private final ObjectProvider<TenantMemberRepository> memberRepoProvider;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final RagService ragService;
    private final LlmFallbackChain fallbackChain;
    private final LiteLlmClient llmClient;
    private final PromptTemplateService templates;
    private final RiskEngineGateway riskEngine;
    private final ProactiveProperties properties;
    private final ObjectMapper objectMapper;

    public ProactiveRiskAnalyzer(ObjectProvider<SavedRouteRepository> routeRepoProvider,
                                 ObjectProvider<TenantMemberRepository> memberRepoProvider,
                                 ObjectProvider<StringRedisTemplate> redisProvider,
                                 RagService ragService,
                                 LlmFallbackChain fallbackChain,
                                 LiteLlmClient llmClient,
                                 PromptTemplateService templates,
                                 RiskEngineGateway riskEngine,
                                 ProactiveProperties properties,
                                 ObjectMapper objectMapper) {
        this.routeRepoProvider = routeRepoProvider;
        this.memberRepoProvider = memberRepoProvider;
        this.redisProvider = redisProvider;
        this.ragService = ragService;
        this.fallbackChain = fallbackChain;
        this.llmClient = llmClient;
        this.templates = templates;
        this.riskEngine = riskEngine;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Called when {@link com.atmospath.platform.risk.AlertStreamService}
     * detects a change in the national alert snapshot. Evaluates all
     * monitored saved routes and returns suggestions for those whose
     * corridor risk now exceeds their threshold.
     */
    public List<RiskSuggestion> analyzeAlertChange(JsonNode alertSnapshot) {
        SavedRouteRepository routeRepo = routeRepoProvider.getIfAvailable();
        if (routeRepo == null) {
            log.debug("SaaS stack unavailable; skipping proactive analysis");
            return List.of();
        }

        List<SavedRouteEntity> monitoredRoutes =
                routeRepo.findByMonitorEnabledTrueAndDeletedAtIsNull();
        if (monitoredRoutes.isEmpty()) {
            return List.of();
        }

        String alertSummary = extractAlertSummary(alertSnapshot);
        String alertHash = sha256Hex(alertSummary);
        List<RiskSuggestion> suggestions = new ArrayList<>();

        for (SavedRouteEntity route : monitoredRoutes) {
            try {
                evaluateRoute(route, alertSummary, alertHash, suggestions);
            } catch (RuntimeException ex) {
                log.warn("Proactive analysis failed for route {}: {}",
                        route.getId(), ex.toString());
            }
        }

        suggestions.sort(Comparator.comparingInt(
                (RiskSuggestion s) -> severityRank(s.severity())).reversed());
        log.info("Proactive analysis complete: {} monitored route(s), {} suggestion(s)",
                monitoredRoutes.size(), suggestions.size());
        return List.copyOf(suggestions);
    }

    // ---- Route evaluation ----

    private void evaluateRoute(SavedRouteEntity route, String alertSummary,
                               String alertHash, List<RiskSuggestion> sink) {
        // Check dedup window
        if (isDuplicate(route.getId(), alertHash)) {
            log.debug("Skipping duplicate suggestion for route {}", route.getId());
            return;
        }

        // Evaluate corridor risk via the risk engine
        int currentRisk = evaluateCorridorRisk(route);
        if (currentRisk < route.getRiskThreshold()) {
            return;
        }

        UUID tenantId = resolveTenantId(route);
        if (tenantId == null) {
            log.warn("Cannot resolve tenant for route {}; skipping", route.getId());
            return;
        }

        // RAG: find similar past situations
        String ragContext = retrieveRagContext(route, alertSummary);

        // Alternative route info from the risk engine
        AlternativeInfo alternative = fetchAlternative(route);

        // LLM suggestion with structured fallback
        String suggestionText = generateSuggestion(route, currentRisk,
                alertSummary, alternative, ragContext);

        String severity = RiskSuggestion.severityForRisk(currentRisk);
        RiskSuggestion suggestion = new RiskSuggestion(
                UUID.randomUUID(), tenantId, route.getId(), route.getName(),
                severity, suggestionText, currentRisk,
                alternative.riskScore(), alternative.description(),
                Instant.now(), false);

        markDedup(route.getId(), alertHash);
        sink.add(suggestion);
    }

    /**
     * Calls the risk engine to evaluate the current risk for the route's
     * corridor. Returns the risk score (0-100), or 0 on failure.
     */
    int evaluateCorridorRisk(SavedRouteEntity route) {
        try {
            var requestBody = objectMapper.createObjectNode();
            requestBody.put("origin", route.getOriginName() != null ? route.getOriginName() : "");
            requestBody.put("destination", route.getDestinationName() != null ? route.getDestinationName() : "");
            requestBody.put("vehicle_type", route.getVehicleType());

            JsonNode response = riskEngine.post("/directions", requestBody);
            return response.path("risk_score").asInt(0);
        } catch (RuntimeException ex) {
            log.warn("Risk engine call failed for route {}: {}", route.getId(), ex.toString());
            return 0;
        }
    }

    // ---- RAG context ----

    private String retrieveRagContext(SavedRouteEntity route, String alertSummary) {
        try {
            String query = alertSummary + " " + route.getName();
            SearchFilters filters = new SearchFilters(
                    route.getId().toString(), null, null, null, null, null);
            RagResult ragResult = ragService.augment(query, filters);
            return ragResult.context();
        } catch (RuntimeException ex) {
            log.debug("RAG retrieval failed for route {}: {}", route.getId(), ex.toString());
            return "No relevant historical data found.";
        }
    }

    // ---- Alternative route ----

    record AlternativeInfo(String description, int riskScore) {
        static AlternativeInfo none() {
            return new AlternativeInfo("No alternative available", 0);
        }
    }

    private AlternativeInfo fetchAlternative(SavedRouteEntity route) {
        try {
            var requestBody = objectMapper.createObjectNode();
            requestBody.put("origin", route.getOriginName() != null ? route.getOriginName() : "");
            requestBody.put("destination", route.getDestinationName() != null ? route.getDestinationName() : "");
            requestBody.put("vehicle_type", route.getVehicleType());
            requestBody.put("alternatives", true);

            JsonNode response = riskEngine.post("/directions", requestBody);
            JsonNode alts = response.path("alternatives");
            if (alts.isArray() && !alts.isEmpty()) {
                JsonNode best = alts.get(0);
                String name = best.path("name").asText("Alternative route");
                int risk = best.path("risk_score").asInt(0);
                int extraMinutes = best.path("extra_duration_minutes").asInt(0);
                String desc = name + (extraMinutes > 0 ? " (+" + extraMinutes + " min)" : "");
                return new AlternativeInfo(desc, risk);
            }
        } catch (RuntimeException ex) {
            log.debug("Alternative fetch failed for route {}: {}", route.getId(), ex.toString());
        }
        return AlternativeInfo.none();
    }

    // ---- LLM suggestion generation ----

    private String generateSuggestion(SavedRouteEntity route, int currentRisk,
                                      String alertSummary, AlternativeInfo alternative,
                                      String ragContext) {
        String routeName = route.getName();
        String origin = route.getOriginName() != null ? route.getOriginName() : "Origin";
        String destination = route.getDestinationName() != null ? route.getDestinationName() : "Destination";
        String language = detectLanguage(routeName);

        Map<String, String> variables = Map.of(
                "route_name", routeName,
                "origin", origin,
                "destination", destination,
                "current_risk", String.valueOf(currentRisk),
                "alert_summary", alertSummary,
                "alternative_info", alternative.description()
                        + " (risk " + alternative.riskScore() + "/100)",
                "rag_context", ragContext,
                "language", language);

        String fallbackText = buildStructuredFallback(routeName, currentRisk,
                alertSummary, alternative);

        var result = fallbackChain.executeWithFallback(
                () -> {
                    List<Message> messages = templates.buildMessages(PROMPT_TEMPLATE, variables);
                    String content = llmClient.complete(messages);
                    return new ChatCompletion(content);
                },
                "proactive:" + route.getId() + ":" + currentRisk,
                () -> fallbackText);

        return result.content();
    }

    private String buildStructuredFallback(String routeName, int currentRisk,
                                           String alertSummary, AlternativeInfo alternative) {
        var sb = new StringBuilder();
        sb.append("Risk alert for saved route: ").append(routeName).append('\n');
        sb.append("Current risk: ").append(currentRisk).append("/100\n");
        sb.append("Alert: ").append(alertSummary).append('\n');
        if (alternative.riskScore() > 0) {
            sb.append("Alternative: ").append(alternative.description())
              .append(" (risk ").append(alternative.riskScore()).append("/100)");
        }
        return sb.toString();
    }

    // ---- Deduplication ----

    private boolean isDuplicate(UUID routeId, String alertHash) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return false;
        }
        try {
            String key = DEDUP_PREFIX + routeId + ":" + alertHash;
            return Boolean.TRUE.equals(redis.hasKey(key));
        } catch (RuntimeException ex) {
            log.debug("Dedup check failed (fail-open): {}", ex.toString());
            return false;
        }
    }

    private void markDedup(UUID routeId, String alertHash) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            String key = DEDUP_PREFIX + routeId + ":" + alertHash;
            redis.opsForValue().set(key, "1",
                    Duration.ofMinutes(properties.dedupWindowMinutes()));
        } catch (RuntimeException ex) {
            log.debug("Dedup mark failed: {}", ex.toString());
        }
    }

    // ---- Helpers ----

    /**
     * Extracts a human-readable summary of active alerts from the national
     * risk snapshot. Limits to the first 5 alert events.
     */
    String extractAlertSummary(JsonNode snapshot) {
        JsonNode alerts = snapshot.path("alerts");
        if (!alerts.isArray() || alerts.isEmpty()) {
            return "No active alerts";
        }
        var sb = new StringBuilder();
        int count = 0;
        for (JsonNode alert : alerts) {
            if (count >= 5) break;
            String event = alert.path("event").asText("Unknown");
            String severity = alert.path("severity").asText("");
            if (count > 0) sb.append("; ");
            sb.append(event);
            if (!severity.isBlank()) sb.append(" (").append(severity).append(')');
            count++;
        }
        return sb.toString();
    }

    private UUID resolveTenantId(SavedRouteEntity route) {
        TenantMemberRepository memberRepo = memberRepoProvider.getIfAvailable();
        if (memberRepo == null) {
            return null;
        }
        return memberRepo.findById(route.getMemberId())
                .map(TenantMember::getTenantId)
                .orElse(null);
    }

    private static String detectLanguage(String text) {
        if (text == null) return "en";
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL) {
                return "ko";
            }
        }
        return "en";
    }

    private static int severityRank(String severity) {
        return switch (severity) {
            case "HIGH" -> 3;
            case "MODERATE" -> 2;
            default -> 1;
        };
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 must be available on the JVM", ex);
        }
    }
}
