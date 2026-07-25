package com.atmospath.platform.llm.context;

import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST endpoints for LLM conversation session management. All routes are
 * conditional on {@code atmospath.llm.enabled=true}.
 */
@RestController
@RequestMapping("/api/v1/llm/session")
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
public class SessionController {

    private final ConversationContextService contextService;

    public SessionController(ConversationContextService contextService) {
        this.contextService = contextService;
    }

    /** Create a new conversation session and return its ID. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> createSession() {
        String sessionId = UUID.randomUUID().toString();
        contextService.getOrCreate(sessionId);
        return Map.of("sessionId", sessionId);
    }

    /** Get session metadata: turn count, constraints presence, last solution. */
    @GetMapping("/{id}")
    public Map<String, Object> getSession(@PathVariable String id) {
        ConversationContext ctx = contextService.getOrCreate(id);
        return Map.of(
                "sessionId", ctx.getSessionId(),
                "turnCount", ctx.getTurns().size(),
                "hasConstraints", ctx.getCurrentConstraints() != null,
                "hasSolution", ctx.getLastSolution() != null,
                "createdAt", ctx.getCreatedAt() != null ? ctx.getCreatedAt().toString() : "",
                "lastActiveAt", ctx.getLastActiveAt() != null ? ctx.getLastActiveAt().toString() : "");
    }

    /** Delete a session and its stored context. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSession(@PathVariable String id) {
        contextService.delete(id);
    }

    /** Get the current accumulated constraints as JSON. */
    @GetMapping("/{id}/constraints")
    public Object getConstraints(@PathVariable String id) {
        ConversationContext ctx = contextService.getOrCreate(id);
        if (ctx.getCurrentConstraints() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No constraints set for session " + id);
        }
        return ctx.getCurrentConstraints();
    }
}
