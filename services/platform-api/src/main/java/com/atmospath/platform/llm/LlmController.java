package com.atmospath.platform.llm;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Public LLM chat endpoints (preview - no auth required, same as
 * {@code /risk/national}). All routes are conditional on
 * {@code atmospath.llm.enabled=true}.
 */
@RestController
@RequestMapping("/api/v1/llm")
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
public class LlmController {

    /** Optional input sanitizer; register a bean to enable sanitization. */
    @FunctionalInterface
    public interface LlmInputSanitizer {
        String sanitize(String input);
    }

    record ChatRequestBody(List<MessageDto> messages) {}

    record MessageDto(String role, String content) {}

    private static final Logger log = LoggerFactory.getLogger(LlmController.class);
    private static final String DEFAULT_TENANT = "default";
    private static final int CHARS_PER_TOKEN = 4;

    private final LlmProperties properties;
    private final LlmStreamService streamService;
    private final LlmTokenBudgetService budgetService;
    private final Optional<LlmInputSanitizer> sanitizer;

    public LlmController(LlmProperties properties,
                         LlmStreamService streamService,
                         LlmTokenBudgetService budgetService,
                         Optional<LlmInputSanitizer> sanitizer) {
        this.properties = properties;
        this.streamService = streamService;
        this.budgetService = budgetService;
        this.sanitizer = sanitizer;
    }

    @PostMapping("/chat")
    public SseEmitter chat(@RequestBody ChatRequestBody body) {
        if (!properties.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "LLM is not enabled");
        }
        if (body.messages() == null || body.messages().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messages must not be empty");
        }

        int estimatedTokens = estimateTokens(body.messages());
        if (!budgetService.canConsume(DEFAULT_TENANT, estimatedTokens)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Daily LLM token budget exceeded");
        }

        List<Message> messages = body.messages().stream()
                .map(m -> new Message(parseRole(m.role()), sanitize(m.content())))
                .toList();

        budgetService.recordUsage(DEFAULT_TENANT, estimatedTokens);
        log.info("LLM chat request: {} message(s), ~{} estimated tokens", messages.size(), estimatedTokens);
        return streamService.streamChat(messages);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        LlmTokenBudgetService.TokenBudgetStatus budget = budgetService.getStatus(DEFAULT_TENANT);
        return Map.of(
                "enabled", properties.enabled(),
                "model", properties.model(),
                "dailyBudgetUsed", budget.usedToday(),
                "dailyBudgetRemaining", budget.remaining());
    }

    private String sanitize(String content) {
        if (content == null) {
            return "";
        }
        return sanitizer.map(s -> s.sanitize(content)).orElse(content);
    }

    private static Message.Role parseRole(String role) {
        if (role == null || role.isBlank()) {
            return Message.Role.USER;
        }
        try {
            return Message.Role.valueOf(role.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Message.Role.USER;
        }
    }

    private static int estimateTokens(List<MessageDto> messages) {
        int totalChars = messages.stream()
                .mapToInt(m -> m.content() != null ? m.content().length() : 0)
                .sum();
        return Math.max(1, totalChars / CHARS_PER_TOKEN);
    }
}
