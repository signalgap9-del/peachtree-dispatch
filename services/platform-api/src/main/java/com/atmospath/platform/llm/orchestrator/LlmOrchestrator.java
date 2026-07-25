package com.atmospath.platform.llm.orchestrator;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.atmospath.platform.llm.LiteLlmClient;
import com.atmospath.platform.llm.LlmProperties;
import com.atmospath.platform.llm.Message;
import com.atmospath.platform.llm.intent.Intent;
import com.atmospath.platform.llm.intent.IntentClassificationService;
import com.atmospath.platform.llm.prompt.PromptTemplateService;
import com.atmospath.platform.llm.security.LlmFallbackChain;
import com.atmospath.platform.llm.security.LlmInputSanitizer;
import com.atmospath.platform.risk.RiskEngineGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Main agent loop that connects intent classification, constraint
 * extraction, route solving, and LLM interpretation into a single
 * conversational pipeline. Each user message flows through:
 * <ol>
 *   <li>Input sanitization</li>
 *   <li>Intent classification</li>
 *   <li>Context loading</li>
 *   <li>Intent-specific handling (extract, solve, interpret)</li>
 *   <li>Context update</li>
 * </ol>
 * All responses are streamed via SSE with progress events so the
 * frontend can show pipeline status.
 */
@Service
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
public class LlmOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(LlmOrchestrator.class);
    private static final String NL2OPT_TEMPLATE = "nl2opt_extraction";

    private final LlmInputSanitizer inputSanitizer;
    private final IntentClassificationService intentService;
    private final ConversationContextService contextService;
    private final RouteInterpretationService interpretationService;
    private final LlmFallbackChain fallbackChain;
    private final LiteLlmClient llmClient;
    private final PromptTemplateService templates;
    private final RiskEngineGateway riskEngine;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper;

    public LlmOrchestrator(LlmInputSanitizer inputSanitizer,
                           IntentClassificationService intentService,
                           ConversationContextService contextService,
                           RouteInterpretationService interpretationService,
                           LlmFallbackChain fallbackChain,
                           LiteLlmClient llmClient,
                           PromptTemplateService templates,
                           RiskEngineGateway riskEngine,
                           LlmProperties properties,
                           ObjectMapper objectMapper) {
        this.inputSanitizer = inputSanitizer;
        this.intentService = intentService;
        this.contextService = contextService;
        this.interpretationService = interpretationService;
        this.fallbackChain = fallbackChain;
        this.llmClient = llmClient;
        this.templates = templates;
        this.riskEngine = riskEngine;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Handles a user message through the full agent pipeline and returns
     * an SSE emitter streaming progress events and LLM interpretation.
     *
     * SSE event types:
     * <ul>
     *   <li>{@code intent} - classified intent</li>
     *   <li>{@code extracting} - constraint extraction in progress</li>
     *   <li>{@code solving} - route optimization in progress</li>
     *   <li>{@code interpreting} - LLM explanation in progress</li>
     *   <li>{@code llm_chunk} - streaming LLM content</li>
     *   <li>{@code llm_done} - stream complete</li>
     *   <li>{@code error} - pipeline error</li>
     * </ul>
     */
    public SseEmitter handleUserMessage(String message, String sessionId, String language) {
        SseEmitter emitter = new SseEmitter(properties.timeoutMs());
        emitter.onCompletion(() -> log.debug("Orchestrator emitter completed: session={}", sessionId));
        emitter.onTimeout(() -> {
            log.debug("Orchestrator emitter timed out: session={}", sessionId);
            emitter.complete();
        });

        String effectiveSession = (sessionId != null && !sessionId.isBlank())
                ? sessionId : UUID.randomUUID().toString();
        String effectiveLanguage = detectLanguage(message, language);

        try {
            // 1. Sanitize input
            var sanitized = inputSanitizer.sanitize(message);
            if (!sanitized.isSafe()) {
                sendError(emitter, "Input rejected: " + String.join(", ", sanitized.detectedPatterns()));
                return emitter;
            }
            String cleanMessage = sanitized.sanitizedText();

            // 2. Classify intent
            Intent intent = intentService.classify(cleanMessage);
            if (intent == Intent.UNKNOWN) {
                intent = Intent.EXPLAIN;
                log.info("Unknown intent mapped to EXPLAIN as default");
            }
            sendProgress(emitter, "intent", intent.name());
            log.info("Pipeline start: session={} intent={} message='{}'",
                    effectiveSession, intent, truncate(cleanMessage));

            // 3. Load conversation context
            ConversationContext context = contextService.load(effectiveSession);

            // 4. Route by intent
            switch (intent) {
                case ROUTE_PLAN -> handleRoutePlan(emitter, cleanMessage, effectiveSession,
                        effectiveLanguage, context);
                case MODIFY -> handleModify(emitter, cleanMessage, effectiveSession,
                        effectiveLanguage, context);
                case COMPARE -> handleCompare(emitter, cleanMessage, effectiveSession,
                        effectiveLanguage, context);
                case FLEET_OPTIMIZE -> handleFleetOptimize(emitter, cleanMessage, effectiveSession,
                        effectiveLanguage, context);
                case EXPLAIN -> handleExplain(emitter, cleanMessage, effectiveLanguage);
                default -> handleExplain(emitter, cleanMessage, effectiveLanguage);
            }
        } catch (RuntimeException ex) {
            log.error("Pipeline error: session={} error={}", effectiveSession, ex.toString(), ex);
            sendError(emitter, "Pipeline error: " + ex.getMessage());
        }

        return emitter;
    }

    // ---- Intent handlers ----

    private void handleRoutePlan(SseEmitter emitter, String message, String sessionId,
                                 String language, ConversationContext context) {
        // Extract constraints
        sendProgress(emitter, "extracting", "Extracting route constraints");
        String constraintsJson = extractConstraints(message);
        log.info("Constraints extracted: session={} json={}", sessionId, truncate(constraintsJson));

        // Solve route
        sendProgress(emitter, "solving", "Optimizing route");
        SolutionResult solution = solveRoute(message, constraintsJson);
        log.info("Route solved: session={} origin={} dest={} risk={}",
                sessionId, solution.origin(), solution.destination(), solution.overallRiskScore());

        // Interpret and stream
        sendProgress(emitter, "interpreting", "Generating explanation");
        List<Message> messages = interpretationService.buildRouteMessages(solution, language);
        String fallback = interpretationService.buildStructuredFallback(solution);
        interpretationService.streamOnto(emitter, messages, fallback);

        // Update context
        contextService.update(sessionId, Intent.ROUTE_PLAN.name(),
                parseConstraints(constraintsJson), solution, List.of(), message);
    }

    private void handleModify(SseEmitter emitter, String message, String sessionId,
                              String language, ConversationContext context) {
        if (!context.hasPreviousSolution()) {
            sendError(emitter, "No previous route to modify. Please plan a route first.");
            return;
        }

        // Extract modification constraints (diff)
        sendProgress(emitter, "extracting", "Extracting modifications");
        String constraintsJson = extractConstraints(message);
        log.info("Modification constraints: session={} json={}", sessionId, truncate(constraintsJson));

        // Re-solve with modified constraints
        sendProgress(emitter, "solving", "Re-optimizing route");
        SolutionResult solution = solveRoute(message, constraintsJson);
        log.info("Route re-solved: session={} risk={}", sessionId, solution.overallRiskScore());

        // Interpret and stream
        sendProgress(emitter, "interpreting", "Generating explanation");
        List<Message> messages = interpretationService.buildRouteMessages(solution, language);
        String fallback = interpretationService.buildStructuredFallback(solution);
        interpretationService.streamOnto(emitter, messages, fallback);

        // Update context
        contextService.update(sessionId, Intent.MODIFY.name(),
                parseConstraints(constraintsJson), solution, List.of(), message);
    }

    private void handleCompare(SseEmitter emitter, String message, String sessionId,
                               String language, ConversationContext context) {
        List<RouteAlternative> alternatives = context.hasAlternatives()
                ? context.lastAlternatives()
                : buildAlternativesFromSolution(context.lastSolution());

        if (alternatives.isEmpty()) {
            sendError(emitter, "No route alternatives to compare. Please plan a route first.");
            return;
        }

        sendProgress(emitter, "interpreting", "Comparing alternatives");
        List<Message> messages = interpretationService.buildComparisonMessages(alternatives, language);
        String fallback = interpretationService.buildComparisonFallback(alternatives);
        interpretationService.streamOnto(emitter, messages, fallback);

        contextService.update(sessionId, Intent.COMPARE.name(),
                context.extractedConstraints(), context.lastSolution(), alternatives, message);
    }

    private void handleFleetOptimize(SseEmitter emitter, String message, String sessionId,
                                     String language, ConversationContext context) {
        // Extract fleet constraints
        sendProgress(emitter, "extracting", "Extracting fleet constraints");
        String constraintsJson = extractConstraints(message);
        log.info("Fleet constraints extracted: session={} json={}", sessionId, truncate(constraintsJson));

        // VRP solve (uses same solver path; full VRP in later phase)
        sendProgress(emitter, "solving", "Optimizing fleet routes");
        SolutionResult solution = solveRoute(message, constraintsJson);
        log.info("Fleet route solved: session={} risk={}", sessionId, solution.overallRiskScore());

        // Interpret and stream
        sendProgress(emitter, "interpreting", "Generating explanation");
        List<Message> messages = interpretationService.buildRouteMessages(solution, language);
        String fallback = interpretationService.buildStructuredFallback(solution);
        interpretationService.streamOnto(emitter, messages, fallback);

        contextService.update(sessionId, Intent.FLEET_OPTIMIZE.name(),
                parseConstraints(constraintsJson), solution, List.of(), message);
    }

    private void handleExplain(SseEmitter emitter, String message, String language) {
        sendProgress(emitter, "interpreting", "Fetching data and generating explanation");
        // ExplainService manages its own emitter internally; for the orchestrator
        // we pipe through by delegating and forwarding events.
        // Since ExplainService returns its own emitter, we use a simpler approach:
        // build the explanation inline using the fallback chain.
        explainViaFallbackChain(emitter, message, language);
    }

    // ---- Constraint extraction ----

    /**
     * Extracts structured VRP constraints from natural language using the
     * nl2opt_extraction prompt template. Falls back to an empty JSON object.
     */
    String extractConstraints(String message) {
        var result = fallbackChain.executeWithFallback(
                () -> {
                    String schema = "{}"; // Schema provided by VrpConstraintSchema in full impl
                    List<Message> messages = templates.buildMessages(NL2OPT_TEMPLATE,
                            Map.of("message", message, "schema", schema));
                    String content = llmClient.complete(messages);
                    return new com.atmospath.platform.llm.security.ChatCompletion(content);
                },
                "nl2opt:" + message.hashCode(),
                () -> "{}");
        return result.content();
    }

    // ---- Route solving ----

    /**
     * Solves the route by calling the risk engine directions endpoint.
     * The full optimization solver (Timefold/OR-Tools) integrates in a
     * later phase; this provides the pipeline structure now.
     */
    SolutionResult solveRoute(String message, String constraintsJson) {
        try {
            var requestBody = objectMapper.createObjectNode();
            requestBody.put("message", message);
            requestBody.set("constraints", objectMapper.readTree(constraintsJson));

            JsonNode response = riskEngine.post("/directions", requestBody);
            return parseSolutionResult(response);
        } catch (RuntimeException ex) {
            log.warn("Route solve failed; returning placeholder: {}", ex.toString());
            return placeholderSolution(message);
        } catch (Exception ex) {
            log.warn("Route solve parse error; returning placeholder: {}", ex.toString());
            return placeholderSolution(message);
        }
    }

    // ---- Explain via fallback chain ----

    private void explainViaFallbackChain(SseEmitter emitter, String message, String language) {
        try {
            JsonNode data = riskEngine.get("/risk/national");
            String dataText = data.toPrettyString();
            String fallback = "National risk data:\n" + dataText;

            String system = """
                    You are a driving safety analyst. Explain the provided data
                    in 2-4 sentences for a professional driver. Focus on what
                    conditions mean for driving safety. Do not invent data that
                    is not present. Use a calm, factual tone.""";
            String user = "Data:\n" + dataText
                    + "\n\nExplain what this means for a driver right now.";
            List<Message> messages = List.of(Message.system(system), Message.user(user));

            var result = fallbackChain.executeWithFallback(
                    () -> {
                        String content = llmClient.complete(messages);
                        return new com.atmospath.platform.llm.security.ChatCompletion(content);
                    },
                    "explain:" + message.hashCode(),
                    () -> fallback);

            sendChunkAndDone(emitter, result.content());
        } catch (RuntimeException ex) {
            log.warn("Explain pipeline failed: {}", ex.toString());
            sendError(emitter, "Unable to generate explanation: " + ex.getMessage());
        }
    }

    // ---- Helpers ----

    private String detectLanguage(String message, String explicitLanguage) {
        if (explicitLanguage != null && !explicitLanguage.isBlank()) {
            return explicitLanguage;
        }
        return RouteInterpretationService.containsKorean(message) ? "ko" : "en";
    }

    private SolutionResult parseSolutionResult(JsonNode response) {
        String origin = response.path("origin").asText("Unknown");
        String destination = response.path("destination").asText("Unknown");
        double distance = response.path("distance_miles").asDouble(0);
        int duration = response.path("duration_minutes").asInt(0);
        int risk = response.path("risk_score").asInt(50);

        var segments = new java.util.ArrayList<RouteSegment>();
        JsonNode segsNode = response.path("segments");
        if (segsNode.isArray()) {
            for (JsonNode seg : segsNode) {
                var alerts = new java.util.ArrayList<String>();
                JsonNode alertsNode = seg.path("alerts");
                if (alertsNode.isArray()) {
                    for (JsonNode a : alertsNode) {
                        alerts.add(a.asText());
                    }
                }
                segments.add(new RouteSegment(
                        seg.path("name").asText("Segment"),
                        seg.path("distance_miles").asDouble(0),
                        seg.path("duration_minutes").asInt(0),
                        seg.path("risk_score").asInt(50),
                        List.copyOf(alerts)));
            }
        }

        var activeAlerts = new java.util.ArrayList<String>();
        JsonNode alertsNode = response.path("active_alerts");
        if (alertsNode.isArray()) {
            for (JsonNode a : alertsNode) {
                activeAlerts.add(a.asText());
            }
        }

        return new SolutionResult(origin, destination, distance, duration, risk,
                List.copyOf(segments), List.copyOf(activeAlerts));
    }

    private SolutionResult placeholderSolution(String message) {
        return new SolutionResult(
                "Origin", "Destination", 0, 0, 50,
                List.of(), List.of("Route data unavailable - solver not yet connected"));
    }

    private List<RouteAlternative> buildAlternativesFromSolution(SolutionResult solution) {
        if (solution == null) {
            return List.of();
        }
        // Build a single alternative from the existing solution
        return List.of(new RouteAlternative(
                "A", solution.origin() + " to " + solution.destination(),
                solution.totalDistanceMiles(), solution.totalDurationMinutes(),
                solution.overallRiskScore(), solution.activeAlerts(), solution.segments()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseConstraints(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception ex) {
            log.debug("Could not parse constraints JSON: {}", ex.toString());
            return Map.of();
        }
    }

    // ---- SSE event helpers ----

    private void sendProgress(SseEmitter emitter, String eventType, String detail) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventType)
                    .data("{\"status\":" + escapeJson(detail) + "}", MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ex) {
            log.debug("Failed to send progress event '{}': {}", eventType, ex.toString());
        }
    }

    private void sendChunkAndDone(SseEmitter emitter, String content) {
        try {
            emitter.send(SseEmitter.event()
                    .name("llm_chunk")
                    .data("{\"content\":" + escapeJson(content) + "}", MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event()
                    .name("llm_done")
                    .data("{\"usage\":{}}", MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException | IllegalStateException ex) {
            log.debug("Failed to send chunk/done: {}", ex.toString());
            completeQuietly(emitter);
        }
    }

    private void sendError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data("{\"error\":" + escapeJson(message) + "}", MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException | IllegalStateException ex) {
            completeQuietly(emitter);
        }
    }

    private static void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // Connection already gone.
        }
    }

    private static String truncate(String text) {
        if (text == null) return "";
        return text.length() <= 80 ? text : text.substring(0, 80) + "...";
    }

    private static String escapeJson(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
