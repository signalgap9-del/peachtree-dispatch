package com.atmospath.platform.llm.proactive;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST endpoints for the proactive risk intelligence subsystem.
 * Exposes pending suggestions, acknowledge/dismiss actions, and
 * analyzer status.
 */
@RestController
@RequestMapping("/api/v1/proactive")
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
public class ProactiveController {

    private final ProactiveSuggestionService suggestionService;
    private final ObjectProvider<ProactiveScheduler> schedulerProvider;

    public ProactiveController(ProactiveSuggestionService suggestionService,
                               ObjectProvider<ProactiveScheduler> schedulerProvider) {
        this.suggestionService = suggestionService;
        this.schedulerProvider = schedulerProvider;
    }

    @GetMapping("/suggestions")
    public List<RiskSuggestion> suggestions(@RequestParam UUID tenantId) {
        return suggestionService.getPendingSuggestions(tenantId);
    }

    @PostMapping("/suggestions/{id}/acknowledge")
    public Map<String, String> acknowledge(@PathVariable UUID id,
                                           @RequestParam UUID tenantId) {
        suggestionService.acknowledgeSuggestion(tenantId, id);
        return Map.of("status", "acknowledged", "suggestionId", id.toString());
    }

    @PostMapping("/suggestions/{id}/dismiss")
    public Map<String, String> dismiss(@PathVariable UUID id,
                                       @RequestParam UUID tenantId) {
        suggestionService.dismissSuggestion(tenantId, id);
        return Map.of("status", "dismissed", "suggestionId", id.toString());
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        ProactiveScheduler scheduler = schedulerProvider.getIfAvailable();
        status.put("enabled", scheduler != null);
        status.put("lastCheckTime", scheduler != null && scheduler.lastCheckTime() != null
                ? scheduler.lastCheckTime().toString() : "never");
        status.put("lastSuggestionCount", scheduler != null
                ? scheduler.lastSuggestionCount() : 0);
        return status;
    }
}
