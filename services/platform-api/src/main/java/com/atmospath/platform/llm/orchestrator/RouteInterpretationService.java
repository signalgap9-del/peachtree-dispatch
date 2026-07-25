package com.atmospath.platform.llm.orchestrator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.atmospath.platform.llm.LiteLlmClient;
import com.atmospath.platform.llm.LlmProperties;
import com.atmospath.platform.llm.LlmStreamService;
import com.atmospath.platform.llm.Message;
import com.atmospath.platform.llm.prompt.PromptTemplateService;
import com.atmospath.platform.llm.rag.RagResult;
import com.atmospath.platform.llm.rag.RagService;
import com.atmospath.platform.llm.rag.SearchFilters;
import com.atmospath.platform.llm.rag.SourceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.scheduler.Schedulers;

/**
 * Converts solver results and weather data into natural-language route
 * explanations. Streams via the LLM when available; degrades to a
 * structured numeric summary otherwise.
 */
@Service
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
public class RouteInterpretationService {

    private static final Logger log = LoggerFactory.getLogger(RouteInterpretationService.class);
    private static final String ROUTE_TEMPLATE = "route_explanation";
    private static final String COMPARISON_TEMPLATE = "comparison_report";

    private final PromptTemplateService templates;
    private final LiteLlmClient llmClient;
    private final LlmStreamService streamService;
    private final LlmProperties properties;
    private final ObjectProvider<RagService> ragProvider;
    private final ObjectProvider<SourceTracker> sourceTrackerProvider;

    public RouteInterpretationService(PromptTemplateService templates,
                                      LiteLlmClient llmClient,
                                      LlmStreamService streamService,
                                      LlmProperties properties,
                                      ObjectProvider<RagService> ragProvider,
                                      ObjectProvider<SourceTracker> sourceTrackerProvider) {
        this.templates = templates;
        this.llmClient = llmClient;
        this.streamService = streamService;
        this.properties = properties;
        this.ragProvider = ragProvider;
        this.sourceTrackerProvider = sourceTrackerProvider;
    }

    // ---- Standalone streaming methods (return their own emitter) ----

    /**
     * Streams a natural-language interpretation of a single route solution.
     * Falls back to structured data if the LLM is unavailable.
     * When RAG is available, augments the prompt with historical observations
     * and appends a citation footer to the fallback.
     */
    public SseEmitter interpretRoute(SolutionResult solution, String language) {
        RagResult ragResult = augmentIfAvailable(solution);
        List<Message> messages = buildRouteMessages(solution, language, ragResult);
        String fallback = buildStructuredFallback(solution)
                + buildRagFooter(ragResult);
        return streamWithFallback(messages, fallback);
    }

    /**
     * Streams a comparison report across multiple route alternatives.
     */
    public SseEmitter interpretComparison(List<RouteAlternative> alternatives, String language) {
        List<Message> messages = buildComparisonMessages(alternatives, language);
        String fallback = buildComparisonFallback(alternatives);
        return streamWithFallback(messages, fallback);
    }

    // ---- Composable streaming (onto an existing emitter) ----

    /**
     * Streams LLM interpretation chunks onto an existing emitter. On LLM
     * failure, sends the structured fallback as a single chunk followed
     * by the done event. The caller retains ownership of the emitter
     * lifecycle for progress events sent before this call.
     */
    public void streamOnto(SseEmitter emitter, List<Message> messages, String fallbackText) {
        var request = new LiteLlmClient.ChatRequest(
                messages, properties.model(), properties.maxTokens(),
                properties.temperature(), true);

        llmClient.chatStream(request)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        chunk -> sendChunk(emitter, chunk),
                        error -> {
                            log.warn("LLM stream failed during interpretation; sending fallback: {}",
                                    error.toString());
                            sendFallbackChunk(emitter, fallbackText);
                        },
                        () -> sendDone(emitter));
    }

    /**
     * Sends the structured fallback text as a single llm_chunk event
     * followed by llm_done. Used when the LLM is unavailable.
     */
    public void sendFallbackChunk(SseEmitter emitter, String text) {
        try {
            emitter.send(SseEmitter.event()
                    .name("llm_chunk")
                    .data("{\"content\":" + escapeJson(text) + "}", MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event()
                    .name("llm_done")
                    .data("{\"usage\":{},\"source\":\"structured\"}", MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException | IllegalStateException ex) {
            log.debug("Failed to send fallback chunk: {}", ex.toString());
            completeQuietly(emitter);
        }
    }

    // ---- Message building ----

    /**
     * Builds the chat messages for route explanation, rendering the
     * {@code route_explanation} template with solution data.
     */
    public List<Message> buildRouteMessages(SolutionResult solution, String language) {
        return buildRouteMessages(solution, language, null);
    }

    /**
     * Builds the chat messages for route explanation, optionally augmented
     * with RAG context from historical observations.
     */
    public List<Message> buildRouteMessages(SolutionResult solution, String language, RagResult ragResult) {
        String segmentsText = formatSegments(solution.segments());
        String alertsText = solution.activeAlerts().isEmpty()
                ? "None"
                : String.join("\n", solution.activeAlerts());

        Map<String, String> vars = Map.of(
                "origin", solution.origin(),
                "destination", solution.destination(),
                "score", solution.overallRiskScore() + "/100 (" + solution.riskLevel() + ")",
                "segments", segmentsText,
                "alerts", alertsText);

        List<Message> messages;
        RagService rag = ragProvider.getIfAvailable();
        if (rag != null && ragResult != null && ragResult.resultCount() > 0) {
            messages = rag.buildRagPrompt(ROUTE_TEMPLATE, vars, ragResult);
        } else {
            messages = templates.buildMessages(ROUTE_TEMPLATE, vars);
        }
        return withLanguageDirective(messages, language);
    }

    /**
     * Builds the chat messages for a comparison report, rendering the
     * {@code comparison_report} template with all alternatives.
     */
    public List<Message> buildComparisonMessages(List<RouteAlternative> alternatives, String language) {
        String alternativesText = IntStream.range(0, alternatives.size())
                .mapToObj(i -> formatAlternative(alternatives.get(i)))
                .collect(Collectors.joining("\n\n"));

        String context = alternatives.isEmpty() ? "No trip context"
                : alternatives.get(0).description();

        Map<String, String> vars = Map.of(
                "context", context,
                "alternatives", alternativesText);

        List<Message> messages = templates.buildMessages(COMPARISON_TEMPLATE, vars);
        return withLanguageDirective(messages, language);
    }

    // ---- Structured fallbacks (no LLM) ----

    /**
     * Builds a structured numeric summary when the LLM is unavailable.
     * Example: "Route: Denver to Boulder. 42.0 miles, 55 min, risk 72/100
     * (High). 3 active alert(s)."
     */
    public String buildStructuredFallback(SolutionResult solution) {
        var sb = new StringBuilder();
        sb.append("Route: ").append(solution.origin()).append(" to ").append(solution.destination()).append(".\n");
        sb.append(String.format("%.1f miles, %d min, risk %d/100 (%s). ",
                solution.totalDistanceMiles(), solution.totalDurationMinutes(),
                solution.overallRiskScore(), solution.riskLevel()));
        sb.append(solution.activeAlerts().size()).append(" active alert(s).\n");

        for (int i = 0; i < solution.segments().size(); i++) {
            var seg = solution.segments().get(i);
            sb.append(String.format("Segment %d (%s): %.1f mi, %d min, risk %d/100",
                    i + 1, seg.name(), seg.distanceMiles(), seg.durationMinutes(), seg.riskScore()));
            if (seg.hasAlerts()) {
                sb.append(". Alerts: ").append(String.join(", ", seg.alerts()));
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Builds a structured comparison summary when the LLM is unavailable.
     * Includes trade-off explanation between the first two alternatives.
     */
    public String buildComparisonFallback(List<RouteAlternative> alternatives) {
        var sb = new StringBuilder("Route Comparison:\n");
        for (var alt : alternatives) {
            sb.append(String.format("Option %s: %.1f miles, %d min, risk %d/100 (%s). %d alert(s).\n",
                    alt.label(), alt.distanceMiles(), alt.durationMinutes(),
                    alt.riskScore(), alt.riskLevel(), alt.alerts().size()));
        }
        if (alternatives.size() >= 2) {
            var a = alternatives.get(0);
            var b = alternatives.get(1);
            int timeDiff = b.durationMinutes() - a.durationMinutes();
            int riskDiff = a.riskScore() - b.riskScore();
            if (timeDiff > 0 && riskDiff > 0) {
                sb.append(String.format(
                        "Trade-off: Option %s is %d min longer but risk drops from %d to %d.\n",
                        b.label(), timeDiff, a.riskScore(), b.riskScore()));
            } else if (timeDiff < 0 && riskDiff < 0) {
                sb.append(String.format(
                        "Trade-off: Option %s is %d min shorter but risk rises from %d to %d.\n",
                        b.label(), -timeDiff, a.riskScore(), b.riskScore()));
            }
        }
        return sb.toString().stripTrailing();
    }

    // ---- Language detection ----

    /** Detects whether the input contains Korean characters. */
    public static boolean containsKorean(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0xAC00 && c <= 0xD7A3)      // Hangul syllables
                    || (c >= 0x1100 && c <= 0x11FF)   // Hangul Jamo
                    || (c >= 0x3130 && c <= 0x318F)) { // Hangul compatibility Jamo
                return true;
            }
        }
        return false;
    }

    // ---- Internals ----

    /**
     * Attempts RAG augmentation for the given solution. Returns null if
     * RagService is unavailable or augmentation yields no results.
     */
    private RagResult augmentIfAvailable(SolutionResult solution) {
        RagService rag = ragProvider.getIfAvailable();
        if (rag == null) {
            return null;
        }
        try {
            String query = solution.origin() + " to " + solution.destination()
                    + " risk " + solution.overallRiskScore();
            SearchFilters filters = SearchFilters.none();
            RagResult result = rag.augment(query, filters);
            return result.resultCount() > 0 ? result : null;
        } catch (Exception ex) {
            log.debug("RAG augmentation failed; proceeding without: {}", ex.toString());
            return null;
        }
    }

    /**
     * Builds a citation footer from RAG sources using the SourceTracker,
     * or returns empty string if no sources are available.
     */
    private String buildRagFooter(RagResult ragResult) {
        if (ragResult == null || ragResult.sources().isEmpty()) {
            return "";
        }
        SourceTracker tracker = sourceTrackerProvider.getIfAvailable();
        if (tracker == null) {
            return "";
        }
        return tracker.buildCitationFooter(ragResult.sources());
    }

    private SseEmitter streamWithFallback(List<Message> messages, String fallbackText) {
        try {
            return streamService.streamChat(messages);
        } catch (RuntimeException ex) {
            log.warn("LLM streaming setup failed; sending structured fallback: {}", ex.toString());
            SseEmitter emitter = new SseEmitter(properties.timeoutMs());
            sendFallbackChunk(emitter, fallbackText);
            return emitter;
        }
    }

    private void sendChunk(SseEmitter emitter, String content) {
        try {
            emitter.send(SseEmitter.event()
                    .name("llm_chunk")
                    .data("{\"content\":" + escapeJson(content) + "}", MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ex) {
            log.debug("Failed to send LLM chunk: {}", ex.toString());
            completeQuietly(emitter);
        }
    }

    private void sendDone(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event()
                    .name("llm_done")
                    .data("{\"usage\":{}}", MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException | IllegalStateException ex) {
            log.debug("Failed to send LLM done event: {}", ex.toString());
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
        var result = new ArrayList<>(messages);
        result.set(0, augmented);
        return List.copyOf(result);
    }

    private String formatSegments(List<RouteSegment> segments) {
        if (segments.isEmpty()) {
            return "No segment data available.";
        }
        return IntStream.range(0, segments.size())
                .mapToObj(i -> {
                    var seg = segments.get(i);
                    var line = String.format("%d. %s: %.1f mi, %d min, risk %d/100",
                            i + 1, seg.name(), seg.distanceMiles(), seg.durationMinutes(), seg.riskScore());
                    if (seg.hasAlerts()) {
                        line += " [" + String.join(", ", seg.alerts()) + "]";
                    }
                    return line;
                })
                .collect(Collectors.joining("\n"));
    }

    private String formatAlternative(RouteAlternative alt) {
        var sb = new StringBuilder();
        sb.append(String.format("Option %s (%s):\n", alt.label(), alt.description()));
        sb.append(String.format("  Distance: %.1f miles\n", alt.distanceMiles()));
        sb.append(String.format("  Duration: %d min\n", alt.durationMinutes()));
        sb.append(String.format("  Risk: %d/100 (%s)\n", alt.riskScore(), alt.riskLevel()));
        if (!alt.alerts().isEmpty()) {
            sb.append("  Alerts: ").append(String.join(", ", alt.alerts())).append("\n");
        }
        if (!alt.segments().isEmpty()) {
            sb.append("  Segments:\n");
            for (int i = 0; i < alt.segments().size(); i++) {
                var seg = alt.segments().get(i);
                sb.append(String.format("    %d. %s (risk %d/100)", i + 1, seg.name(), seg.riskScore()));
                if (seg.hasAlerts()) {
                    sb.append(" [").append(String.join(", ", seg.alerts())).append("]");
                }
                sb.append("\n");
            }
        }
        return sb.toString().stripTrailing();
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
