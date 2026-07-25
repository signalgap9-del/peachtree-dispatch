package com.atmospath.platform.llm.orchestrator;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * REST controller for the LLM orchestrator. Exposes the main
 * conversational planning endpoint and a context inspection endpoint
 * for debugging.
 */
@RestController
@RequestMapping("/api/v1/llm")
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
public class OrchestratorController {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorController.class);

    private final LlmOrchestrator orchestrator;
    private final ConversationContextService contextService;

    public OrchestratorController(LlmOrchestrator orchestrator,
                                  ConversationContextService contextService) {
        this.orchestrator = orchestrator;
        this.contextService = contextService;
    }

    record PlanRequest(String message, String sessionId, String language) {}

    /**
     * Main conversational endpoint. Accepts a natural-language message
     * and returns an SSE stream with pipeline progress events and LLM
     * interpretation chunks.
     *
     * SSE event types: intent, extracting, solving, interpreting,
     * llm_chunk, llm_done, error.
     */
    @PostMapping("/plan")
    public SseEmitter plan(@RequestBody PlanRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message must not be empty");
        }
        log.info("Plan request: message='{}' session={} lang={}",
                truncate(request.message()), request.sessionId(), request.language());
        return orchestrator.handleUserMessage(
                request.message(), request.sessionId(), request.language());
    }

    /**
     * Returns the current conversation context for a session.
     * Useful for debugging and frontend status display.
     */
    @GetMapping("/context/{sessionId}")
    public Map<String, Object> context(@PathVariable String sessionId) {
        ConversationContext ctx = contextService.load(sessionId);
        return Map.of(
                "sessionId", ctx.sessionId(),
                "lastIntent", ctx.lastIntent() != null ? ctx.lastIntent() : "none",
                "hasPreviousSolution", ctx.hasPreviousSolution(),
                "hasAlternatives", ctx.hasAlternatives(),
                "lastUserMessage", ctx.lastUserMessage() != null ? ctx.lastUserMessage() : "",
                "updatedAt", ctx.updatedAt().toString());
    }

    private static String truncate(String text) {
        if (text == null) return "";
        return text.length() <= 60 ? text : text.substring(0, 60) + "...";
    }
}
