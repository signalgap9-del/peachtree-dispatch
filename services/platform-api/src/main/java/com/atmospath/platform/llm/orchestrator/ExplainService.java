package com.atmospath.platform.llm.orchestrator;

import java.util.List;
import java.util.Map;

import com.atmospath.platform.llm.LiteLlmClient;
import com.atmospath.platform.llm.LlmProperties;
import com.atmospath.platform.llm.Message;
import com.atmospath.platform.llm.prompt.PromptTemplateService;
import com.atmospath.platform.llm.security.ChatCompletion;
import com.atmospath.platform.llm.security.LlmFallbackChain;
import com.atmospath.platform.llm.security.LlmOutputValidator;
import com.atmospath.platform.risk.RiskEngineGateway;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Handles the EXPLAIN intent: fetches live risk/alert data from the risk
 * engine and uses the LLM to produce a driver-facing explanation. All
 * methods degrade to a raw-data summary when the LLM is unavailable.
 */
@Service
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
public class ExplainService {

    private static final Logger log = LoggerFactory.getLogger(ExplainService.class);
    private static final String ALERT_TEMPLATE = "alert_summary";

    private final RiskEngineGateway riskEngine;
    private final LiteLlmClient llmClient;
    private final PromptTemplateService templates;
    private final LlmFallbackChain fallbackChain;
    private final LlmOutputValidator outputValidator;
    private final LlmProperties properties;

    public ExplainService(RiskEngineGateway riskEngine,
                          LiteLlmClient llmClient,
                          PromptTemplateService templates,
                          LlmFallbackChain fallbackChain,
                          LlmOutputValidator outputValidator,
                          LlmProperties properties) {
        this.riskEngine = riskEngine;
        this.llmClient = llmClient;
        this.templates = templates;
        this.fallbackChain = fallbackChain;
        this.outputValidator = outputValidator;
        this.properties = properties;
    }

    /**
     * Fetches the national risk overview and streams an LLM explanation
     * of the current driving situation.
     */
    public SseEmitter explainNationalRisk(String language) {
        SseEmitter emitter = new SseEmitter(properties.timeoutMs());
        try {
            JsonNode data = riskEngine.get("/risk/national");
            String dataText = data.toPrettyString();
            String fallback = "National risk data:\n" + dataText;

            List<Message> messages = buildExplainMessages(
                    "current national driving risk overview", dataText, language);

            streamExplanation(emitter, messages, fallback, "explain:national");
        } catch (RuntimeException ex) {
            log.warn("Failed to fetch national risk data: {}", ex.toString());
            sendError(emitter, "Unable to fetch national risk data: " + ex.getMessage());
        }
        return emitter;
    }

    /**
     * Fetches location-specific risk and streams an LLM explanation.
     */
    public SseEmitter explainLocationRisk(double lat, double lon, String language) {
        SseEmitter emitter = new SseEmitter(properties.timeoutMs());
        try {
            var body = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            body.put("lat", lat);
            body.put("lon", lon);
            JsonNode data = riskEngine.post("/risk/location", body);
            String dataText = data.toPrettyString();
            String fallback = String.format("Risk at (%.4f, %.4f):\n%s", lat, lon, dataText);

            List<Message> messages = buildExplainMessages(
                    String.format("driving risk at coordinates (%.4f, %.4f)", lat, lon),
                    dataText, language);

            streamExplanation(emitter, messages, fallback, "explain:location:" + lat + ":" + lon);
        } catch (RuntimeException ex) {
            log.warn("Failed to fetch location risk data: {}", ex.toString());
            sendError(emitter, "Unable to fetch location risk data: " + ex.getMessage());
        }
        return emitter;
    }

    /**
     * Searches alerts matching the query and streams an LLM summary.
     */
    public SseEmitter explainAlerts(String query, String language) {
        SseEmitter emitter = new SseEmitter(properties.timeoutMs());
        try {
            JsonNode data = riskEngine.get("/alerts/search?q=" + query);
            String dataText = data.toPrettyString();
            String fallback = "Alerts for '" + query + "':\n" + dataText;

            List<Message> messages;
            if (templates.templateNames().contains(ALERT_TEMPLATE)) {
                messages = templates.buildMessages(ALERT_TEMPLATE, Map.of("alert_text", dataText));
                messages = withLanguageDirective(messages, language);
            } else {
                messages = buildExplainMessages("weather alerts for: " + query, dataText, language);
            }

            streamExplanation(emitter, messages, fallback, "explain:alerts:" + query.hashCode());
        } catch (RuntimeException ex) {
            log.warn("Failed to fetch alert data: {}", ex.toString());
            sendError(emitter, "Unable to fetch alert data: " + ex.getMessage());
        }
        return emitter;
    }

    /**
     * General explanation: routes to the appropriate data source based on
     * the user message content.
     */
    public SseEmitter explain(String userMessage, String language) {
        String lower = userMessage.toLowerCase();
        if (lower.contains("national") || lower.contains("overall") || lower.contains("country")) {
            return explainNationalRisk(language);
        }
        if (lower.contains("alert") || lower.contains("warning") || lower.contains("weather")) {
            return explainAlerts(userMessage, language);
        }
        // Default: national risk overview
        return explainNationalRisk(language);
    }

    // ---- Internals ----

    private List<Message> buildExplainMessages(String topic, String data, String language) {
        String system = """
                You are a driving safety analyst. Explain the provided data
                in 2-4 sentences for a professional driver. Focus on what
                conditions mean for driving safety. Do not invent data that
                is not present. Use a calm, factual tone.""";
        String user = "Topic: " + topic + "\n\nData:\n" + data
                + "\n\nExplain what this means for a driver right now.";
        List<Message> messages = List.of(Message.system(system), Message.user(user));
        return withLanguageDirective(messages, language);
    }

    private void streamExplanation(SseEmitter emitter, List<Message> messages,
                                   String fallbackText, String cacheKey) {
        var result = fallbackChain.executeWithFallback(
                () -> {
                    String content = llmClient.complete(messages);
                    var validation = outputValidator.validate(content);
                    return new ChatCompletion(validation.sanitizedOutput());
                },
                cacheKey,
                () -> fallbackText);

        // Send the result as a single chunk + done (non-streaming explain)
        try {
            emitter.send(SseEmitter.event()
                    .name("llm_chunk")
                    .data("{\"content\":" + escapeJson(result.content()) + "}", MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event()
                    .name("llm_done")
                    .data("{\"usage\":{},\"source\":\"" + result.source().name().toLowerCase() + "\"}",
                            MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (Exception ex) {
            log.debug("Failed to send explanation: {}", ex.toString());
            completeQuietly(emitter);
        }
    }

    private void sendError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data("{\"error\":" + escapeJson(message) + "}", MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (Exception ex) {
            completeQuietly(emitter);
        }
    }

    private List<Message> withLanguageDirective(List<Message> messages, String language) {
        boolean korean = "ko".equalsIgnoreCase(language)
                || "korean".equalsIgnoreCase(language);
        if (!korean || messages.isEmpty()) {
            return messages;
        }
        Message system = messages.get(0);
        Message augmented = Message.system(
                system.content() + "\n\nRespond in Korean (한국어로 응답하세요).");
        var result = new java.util.ArrayList<>(messages);
        result.set(0, augmented);
        return List.copyOf(result);
    }

    private static void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // Connection already gone.
        }
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
