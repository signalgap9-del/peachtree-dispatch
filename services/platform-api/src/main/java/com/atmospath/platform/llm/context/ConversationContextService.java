package com.atmospath.platform.llm.context;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.atmospath.platform.llm.LlmClient;
import com.atmospath.platform.llm.Message;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Manages conversation context lifecycle: creation, persistence (Redis with
 * in-memory fallback), context-window construction, and constraint diff
 * detection. Redis access is fail-open; if Redis is unavailable the service
 * degrades to a local in-memory map so the conversation never crashes.
 */
@Service
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
public class ConversationContextService {

    static final String KEY_PREFIX = "llm:context:";
    static final Duration SESSION_TTL = Duration.ofHours(2);
    private static final int VERBATIM_TURNS = 5;
    private static final int CHARS_PER_TOKEN = 4;

    private static final Logger log = LoggerFactory.getLogger(ConversationContextService.class);

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectProvider<LlmClient> llmProvider;
    private final ContextSummarizer summarizer;
    private final ObjectMapper objectMapper;

    /** In-memory fallback when Redis is unavailable. */
    private final Map<String, String> localStore = new ConcurrentHashMap<>();

    public ConversationContextService(ObjectProvider<StringRedisTemplate> redisProvider,
                                      ObjectProvider<LlmClient> llmProvider,
                                      ContextSummarizer summarizer,
                                      ObjectMapper objectMapper) {
        this.redisProvider = redisProvider;
        this.llmProvider = llmProvider;
        this.summarizer = summarizer;
        this.objectMapper = objectMapper;
    }

    /** Retrieve an existing context or create a fresh one for the session. */
    public ConversationContext getOrCreate(String sessionId) {
        String json = read(sessionId);
        if (json != null) {
            try {
                ConversationContext ctx = objectMapper.readValue(json, ConversationContext.class);
                if (!ctx.isExpired(SESSION_TTL)) {
                    refreshTtl(sessionId);
                    return ctx;
                }
                log.info("Session {} expired, creating new context", sessionId);
            } catch (JsonProcessingException e) {
                log.warn("Corrupt context for session {}, recreating: {}", sessionId, e.getMessage());
            }
        }
        ConversationContext ctx = new ConversationContext(sessionId);
        save(ctx);
        return ctx;
    }

    /** Persist context to Redis (or in-memory fallback) with TTL. */
    public void save(ConversationContext context) {
        try {
            String json = objectMapper.writeValueAsString(context);
            String key = KEY_PREFIX + context.getSessionId();

            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis != null) {
                redis.opsForValue().set(key, json, SESSION_TTL);
                localStore.remove(context.getSessionId());
            } else {
                localStore.put(context.getSessionId(), json);
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize context for session {}: {}",
                    context.getSessionId(), e.getMessage());
        } catch (Exception e) {
            log.warn("Redis write failed for session {}, using in-memory: {}",
                    context.getSessionId(), e.getMessage());
            try {
                localStore.put(context.getSessionId(), objectMapper.writeValueAsString(context));
            } catch (JsonProcessingException ignored) {
                // already logged above
            }
        }
    }

    /** Delete a session's context from both Redis and local fallback. */
    public void delete(String sessionId) {
        localStore.remove(sessionId);
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis != null) {
                redis.delete(KEY_PREFIX + sessionId);
            }
        } catch (Exception e) {
            log.warn("Redis delete failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Build the LLM context window. If total turns exceed the token budget,
     * older turns are summarized into a single system message while the last
     * {@value VERBATIM_TURNS} turns are kept verbatim.
     */
    public List<Message> buildContextWindow(ConversationContext ctx, int maxTokens) {
        List<ConversationContext.Turn> turns = ctx.getTurns();
        if (turns.isEmpty()) {
            return List.of();
        }

        int estimatedTokens = turns.stream()
                .mapToInt(t -> t.content() != null ? t.content().length() / CHARS_PER_TOKEN : 0)
                .sum();

        if (estimatedTokens <= maxTokens || turns.size() <= VERBATIM_TURNS) {
            return ctx.toLlmMessages(turns.size());
        }

        // Summarize older turns, keep recent ones verbatim
        List<ConversationContext.Turn> oldTurns = turns.subList(0, turns.size() - VERBATIM_TURNS);
        String summary = summarizer.summarize(oldTurns, ctx);

        List<Message> window = new ArrayList<>();
        if (!summary.isBlank()) {
            window.add(Message.system(summary));
        }
        window.addAll(ctx.toLlmMessages(VERBATIM_TURNS));
        return window;
    }

    /**
     * Detect what constraint changes the user's message implies. Tries the
     * LLM first; falls back to keyword heuristics.
     */
    public ConstraintDiff detectDiff(String userMessage, ConversationContext ctx) {
        LlmClient llm = llmProvider.getIfAvailable();
        if (llm != null) {
            try {
                ConstraintDiff diff = detectDiffViaLlm(userMessage, ctx, llm);
                if (diff != null) {
                    return diff;
                }
            } catch (Exception e) {
                log.warn("LLM diff detection failed, using keyword fallback: {}", e.getMessage());
            }
        }
        return detectDiffByKeywords(userMessage);
    }

    private ConstraintDiff detectDiffViaLlm(String userMessage, ConversationContext ctx, LlmClient llm)
            throws JsonProcessingException {
        String constraintsJson = ctx.getCurrentConstraints() != null
                ? objectMapper.writeValueAsString(ctx.getCurrentConstraints())
                : "{}";

        String prompt = "Current constraints: " + constraintsJson + "\n"
                + "User says: '" + userMessage + "'\n"
                + "What fields should change? Output a JSON object with keys: "
                + "\"modifiedFields\" (object of dot-path to new value), "
                + "\"addedConstraints\" (array of constraint JSON strings), "
                + "\"removedConstraints\" (array of constraint type/target strings to remove). "
                + "If nothing changes, return {\"modifiedFields\":{},\"addedConstraints\":[],\"removedConstraints\":[]}.";

        String response = llm.complete(List.of(
                Message.system("You are a constraint diff detector for a route-planning assistant. "
                        + "Respond with valid JSON only."),
                Message.user(prompt)));

        if (response == null || response.isBlank()) {
            return null;
        }
        // Strip markdown fences if present
        String cleaned = response.strip();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
        }
        return objectMapper.readValue(cleaned, ConstraintDiff.class);
    }

    /** Keyword-based fallback for diff detection when LLM is unavailable. */
    ConstraintDiff detectDiffByKeywords(String message) {
        if (message == null || message.isBlank()) {
            return new ConstraintDiff(Map.of(), List.of(), List.of());
        }
        String lower = message.toLowerCase();

        // Time modification: "later", "뒤에", "일찍", "earlier"
        if (lower.contains("later") || lower.contains("뒤에") || lower.contains("늦게")) {
            return new ConstraintDiff(Map.of("departure.flexibility", "flexible"), List.of(), List.of());
        }
        if (lower.contains("earlier") || lower.contains("일찍") || lower.contains("빨리")) {
            return new ConstraintDiff(Map.of("departure.flexibility", "flexible"), List.of(), List.of());
        }

        // Avoid: "avoid", "피해", "피할"
        if (lower.contains("avoid") || lower.contains("피해") || lower.contains("피할")) {
            String target = extractTargetAfterKeyword(lower, "avoid");
            if (target == null) target = extractTargetAfterKeyword(lower, "피해");
            if (target == null) target = extractTargetAfterKeyword(lower, "피할");
            if (target != null) {
                String json = "{\"type\":\"avoid_corridor\",\"target\":\"" + target + "\"}";
                return new ConstraintDiff(Map.of(), List.of(json), List.of());
            }
        }

        // Remove: "remove", "빼", "제거"
        if (lower.contains("remove") || lower.contains("빼") || lower.contains("제거")) {
            String target = extractTargetAfterKeyword(lower, "remove");
            if (target == null) target = extractTargetAfterKeyword(lower, "빼");
            if (target != null) {
                return new ConstraintDiff(Map.of(), List.of(), List.of(target));
            }
        }

        return new ConstraintDiff(Map.of(), List.of(), List.of());
    }

    private static String extractTargetAfterKeyword(String lower, String keyword) {
        int idx = lower.indexOf(keyword);
        if (idx < 0) {
            return null;
        }
        String rest = lower.substring(idx + keyword.length()).strip();
        // Take the next word or quoted phrase
        if (rest.isEmpty()) {
            return null;
        }
        String[] words = rest.split("\\s+");
        return words[0].replaceAll("[^a-zA-Z0-9\\-]", "");
    }

    // --- Redis helpers (fail-open) ---

    private String read(String sessionId) {
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis != null) {
                String value = redis.opsForValue().get(KEY_PREFIX + sessionId);
                if (value != null) {
                    return value;
                }
            }
        } catch (Exception e) {
            log.warn("Redis read failed for session {}, trying local: {}", sessionId, e.getMessage());
        }
        return localStore.get(sessionId);
    }

    private void refreshTtl(String sessionId) {
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis != null) {
                redis.expire(KEY_PREFIX + sessionId, SESSION_TTL);
            }
        } catch (Exception e) {
            log.debug("TTL refresh failed for session {}: {}", sessionId, e.getMessage());
        }
    }
}
