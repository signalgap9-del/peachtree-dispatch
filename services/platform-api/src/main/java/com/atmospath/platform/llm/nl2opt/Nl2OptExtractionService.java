package com.atmospath.platform.llm.nl2opt;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.atmospath.platform.llm.LlmClient;
import com.atmospath.platform.llm.Message;
import com.atmospath.platform.llm.nl2opt.ExtractionResult.LlmUsage;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.VrpConstraints;
import com.atmospath.platform.llm.nl2opt.VrpConstraintValidator.ValidationResult;
import com.atmospath.platform.llm.prompt.PromptTemplateService;
import com.atmospath.platform.risk.RiskEngineGateway;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Extracts structured VRP constraints from natural language using the
 * OptiMUS Phase 1 pattern: prompt → parse → validate → repair loop.
 * After validation succeeds, place names are geocoded via the risk
 * engine's {@code /places/search} endpoint. Unresolvable places are
 * reported but do not fail the extraction.
 */
@Service
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
public class Nl2OptExtractionService {

    private static final Logger log = LoggerFactory.getLogger(Nl2OptExtractionService.class);
    private static final String TEMPLATE_NAME = "nl2opt_extraction";
    private static final int MAX_REPAIR_ATTEMPTS = 3;
    private static final int CHARS_PER_TOKEN_ESTIMATE = 4;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final LlmClient llmClient;
    private final PromptTemplateService templates;
    private final VrpConstraintValidator validator;
    private final RiskEngineGateway riskEngine;

    public Nl2OptExtractionService(LlmClient llmClient,
                                   PromptTemplateService templates,
                                   VrpConstraintValidator validator,
                                   RiskEngineGateway riskEngine) {
        this.llmClient = llmClient;
        this.templates = templates;
        this.validator = validator;
        this.riskEngine = riskEngine;
    }

    /**
     * Main entry point: extracts VRP constraints from a user message,
     * optionally incorporating previous conversation state, then geocodes
     * stop names.
     *
     * @throws ExtractionFailedException if the LLM cannot produce valid
     *         constraints within {@value MAX_REPAIR_ATTEMPTS} attempts
     */
    public ExtractionResult extract(String userMessage, ConversationContext context) {
        List<Message> messages = buildInitialMessages(userMessage, context);
        int promptChars = messages.stream().mapToInt(m -> m.content().length()).sum();

        String rawJson = llmClient.complete(messages);
        int completionChars = rawJson.length();
        int repairAttempts = 0;

        ValidationResult result = validator.validate(stripCodeFences(rawJson));

        while (!result.valid() && repairAttempts < MAX_REPAIR_ATTEMPTS) {
            repairAttempts++;
            log.info("Repair attempt {}/{}: errors={}", repairAttempts, MAX_REPAIR_ATTEMPTS, result.errors());

            List<Message> repairMessages = new ArrayList<>(messages);
            repairMessages.add(new Message(Message.Role.ASSISTANT, rawJson));
            repairMessages.add(Message.user(buildRepairPrompt(result.errors())));

            promptChars += repairMessages.stream().mapToInt(m -> m.content().length()).sum();
            rawJson = llmClient.complete(repairMessages);
            completionChars += rawJson.length();
            result = validator.validate(stripCodeFences(rawJson));
        }

        if (!result.valid()) {
            throw new ExtractionFailedException(result.errors(), repairAttempts);
        }

        VrpConstraints constraints = parseConstraints(stripCodeFences(rawJson));
        log.info("Extraction succeeded: stops={} repairAttempts={}", constraints.stops().size(), repairAttempts);

        GeocodingResult geocoding = geocodeStops(constraints);
        LlmUsage usage = new LlmUsage(
                promptChars / CHARS_PER_TOKEN_ESTIMATE,
                completionChars / CHARS_PER_TOKEN_ESTIMATE);

        return new ExtractionResult(constraints, geocoding.resolved(), geocoding.unresolved(), repairAttempts, usage);
    }

    private List<Message> buildInitialMessages(String userMessage, ConversationContext context) {
        String effectiveMessage = userMessage;
        if (context != null && context.hasPreviousConstraints()) {
            effectiveMessage = buildContextualMessage(userMessage, context);
        }
        return templates.buildMessages(TEMPLATE_NAME, Map.of(
                "schema", VrpConstraintSchema.JSON_SCHEMA,
                "message", effectiveMessage));
    }

    private String buildContextualMessage(String userMessage, ConversationContext context) {
        try {
            String previousJson = MAPPER.writeValueAsString(context.previousConstraints());
            return "Current state (previous constraints):\n" + previousJson
                    + "\n\nUser request: " + userMessage;
        } catch (Exception ex) {
            log.warn("Could not serialize previous constraints; proceeding without context", ex);
            return userMessage;
        }
    }

    private static String buildRepairPrompt(List<String> errors) {
        return "Previous output had errors:\n" + String.join("\n", errors)
                + "\n\nFix the errors and output valid JSON matching the schema. Output only JSON.";
    }

    private VrpConstraints parseConstraints(String json) {
        try {
            return MAPPER.readValue(json, VrpConstraints.class);
        } catch (Exception ex) {
            // Should not happen since validation passed, but guard anyway
            throw new ExtractionFailedException(
                    List.of("JSON parse failed after validation: " + ex.getMessage()), MAX_REPAIR_ATTEMPTS);
        }
    }

    private GeocodingResult geocodeStops(VrpConstraints constraints) {
        Map<String, GeoPoint> resolved = new LinkedHashMap<>();
        List<String> unresolved = new ArrayList<>();

        if (constraints.stops() == null) {
            return new GeocodingResult(resolved, unresolved);
        }

        for (var stop : constraints.stops()) {
            if (stop.name() == null || stop.name().isBlank()) {
                continue;
            }
            String name = stop.name().trim();
            if (resolved.containsKey(name)) {
                continue;
            }
            GeoPoint point = geocode(name);
            if (point != null) {
                resolved.put(name, point);
            } else {
                unresolved.add(name);
            }
        }
        return new GeocodingResult(resolved, unresolved);
    }

    private GeoPoint geocode(String placeName) {
        try {
            String encoded = URLEncoder.encode(placeName, StandardCharsets.UTF_8);
            JsonNode response = riskEngine.get("/places/search?q=" + encoded);
            if (response == null || !response.isArray() || response.isEmpty()) {
                log.debug("No geocoding results for '{}'", placeName);
                return null;
            }
            JsonNode first = response.get(0);
            double lat = first.path("lat").asDouble(Double.NaN);
            double lon = first.path("lon").asDouble(Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon)) {
                log.debug("Geocoding result for '{}' missing coordinates", placeName);
                return null;
            }
            String displayName = first.path("display_name").asText(placeName);
            String source = first.path("source").asText("nominatim");
            return new GeoPoint(lat, lon, displayName, source);
        } catch (RuntimeException ex) {
            log.warn("Geocoding failed for '{}': {}", placeName, ex.toString());
            return null;
        }
    }

    /** Strips markdown code fences the LLM sometimes wraps around JSON. */
    static String stripCodeFences(String raw) {
        String trimmed = raw.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
            return trimmed.strip();
        }
        return trimmed;
    }

    private record GeocodingResult(Map<String, GeoPoint> resolved, List<String> unresolved) {
    }
}
