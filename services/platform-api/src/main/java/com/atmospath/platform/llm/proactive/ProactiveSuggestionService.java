package com.atmospath.platform.llm.proactive;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.atmospath.platform.risk.AlertStreamService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Manages the lifecycle of proactive risk suggestions: storage in Redis
 * (24-hour TTL), SSE delivery to connected clients, and user actions
 * (acknowledge / dismiss).
 *
 * <p>Suggestions are stored in a Redis hash keyed by
 * {@code llm:suggestions:{tenantId}} where each field is the suggestion
 * UUID and the value is the JSON-serialized {@link RiskSuggestion}.
 */
@Service
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
public class ProactiveSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(ProactiveSuggestionService.class);
    static final String SUGGESTIONS_PREFIX = "llm:suggestions:";
    static final Duration SUGGESTION_TTL = Duration.ofHours(24);
    static final String SSE_EVENT = "risk_suggestion";

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final AlertStreamService alertStream;
    private final ProactiveProperties properties;
    private final ObjectMapper objectMapper;

    public ProactiveSuggestionService(ObjectProvider<StringRedisTemplate> redisProvider,
                                      AlertStreamService alertStream,
                                      ProactiveProperties properties,
                                      ObjectMapper objectMapper) {
        this.redisProvider = redisProvider;
        this.alertStream = alertStream;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Stores a suggestion in Redis and pushes it to connected SSE clients.
     * Enforces the per-tenant suggestion limit; the oldest suggestion is
     * evicted when the limit is exceeded.
     */
    public void publishSuggestion(RiskSuggestion suggestion) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            log.warn("Redis unavailable; suggestion {} not persisted", suggestion.id());
            pushToSseStream(suggestion);
            return;
        }

        String key = SUGGESTIONS_PREFIX + suggestion.tenantId();
        try {
            enforceLimit(redis, key);
            String json = objectMapper.writeValueAsString(suggestion);
            redis.opsForHash().put(key, suggestion.id().toString(), json);
            redis.expire(key, SUGGESTION_TTL);
            log.info("Suggestion {} published for tenant {} (route={})",
                    suggestion.id(), suggestion.tenantId(), suggestion.routeName());
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize suggestion {}: {}", suggestion.id(), ex.toString());
            return;
        } catch (RuntimeException ex) {
            log.warn("Redis write failed for suggestion {}: {}", suggestion.id(), ex.toString());
        }

        pushToSseStream(suggestion);
    }

    /**
     * Returns all pending (non-acknowledged, non-dismissed) suggestions
     * for a tenant, sorted by severity then creation time.
     */
    public List<RiskSuggestion> getPendingSuggestions(UUID tenantId) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return List.of();
        }

        String key = SUGGESTIONS_PREFIX + tenantId;
        try {
            Map<Object, Object> entries = redis.opsForHash().entries(key);
            List<RiskSuggestion> suggestions = new ArrayList<>();
            for (Object value : entries.values()) {
                RiskSuggestion s = objectMapper.readValue(
                        value.toString(), RiskSuggestion.class);
                if (!s.acknowledged()) {
                    suggestions.add(s);
                }
            }
            suggestions.sort(Comparator
                    .comparingInt((RiskSuggestion s) -> severityRank(s.severity())).reversed()
                    .thenComparing(RiskSuggestion::createdAt));
            return List.copyOf(suggestions);
        } catch (RuntimeException ex) {
            log.warn("Redis read failed for tenant {}: {}", tenantId, ex.toString());
            return List.of();
        }
    }

    /** Marks a suggestion as acknowledged; it remains in storage but is excluded from pending. */
    public void acknowledgeSuggestion(UUID tenantId, UUID suggestionId) {
        updateFlag(tenantId, suggestionId, true);
        log.info("Suggestion {} acknowledged by tenant {}", suggestionId, tenantId);
    }

    /** Removes a suggestion from storage entirely. */
    public void dismissSuggestion(UUID tenantId, UUID suggestionId) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        String key = SUGGESTIONS_PREFIX + tenantId;
        try {
            redis.opsForHash().delete(key, suggestionId.toString());
            log.info("Suggestion {} dismissed by tenant {}", suggestionId, tenantId);
        } catch (RuntimeException ex) {
            log.warn("Redis delete failed for suggestion {}: {}", suggestionId, ex.toString());
        }
    }

    /** Returns the total suggestion count for a tenant (including acknowledged). */
    public long suggestionCount(UUID tenantId) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return 0;
        }
        try {
            Long size = redis.opsForHash().size(SUGGESTIONS_PREFIX + tenantId);
            return size != null ? size : 0;
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    // ---- SSE delivery ----

    void pushToSseStream(RiskSuggestion suggestion) {
        try {
            String json = objectMapper.writeValueAsString(suggestion);
            alertStream.broadcastEvent(SSE_EVENT, json);
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize suggestion for SSE: {}", ex.toString());
        } catch (RuntimeException ex) {
            log.debug("SSE broadcast failed: {}", ex.toString());
        }
    }

    // ---- Internals ----

    private void updateFlag(UUID tenantId, UUID suggestionId, boolean acknowledged) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        String key = SUGGESTIONS_PREFIX + tenantId;
        try {
            Object raw = redis.opsForHash().get(key, suggestionId.toString());
            if (raw == null) {
                log.debug("Suggestion {} not found for tenant {}", suggestionId, tenantId);
                return;
            }
            RiskSuggestion existing = objectMapper.readValue(
                    raw.toString(), RiskSuggestion.class);
            RiskSuggestion updated = existing.withAcknowledged(acknowledged);
            redis.opsForHash().put(key, suggestionId.toString(),
                    objectMapper.writeValueAsString(updated));
        } catch (RuntimeException ex) {
            log.warn("Redis update failed for suggestion {}: {}", suggestionId, ex.toString());
        }
    }

    /** Evicts the oldest suggestion when the per-tenant limit is reached. */
    private void enforceLimit(StringRedisTemplate redis, String key) {
        Long size = redis.opsForHash().size(key);
        if (size == null || size < properties.maxSuggestionsPerTenant()) {
            return;
        }
        try {
            Map<Object, Object> entries = redis.opsForHash().entries(key);
            entries.values().stream()
                    .map(v -> {
                        try {
                            return objectMapper.readValue(v.toString(), RiskSuggestion.class);
                        } catch (JsonProcessingException ex) {
                            return null;
                        }
                    })
                    .filter(s -> s != null)
                    .min(Comparator.comparing(RiskSuggestion::createdAt))
                    .ifPresent(oldest -> {
                        redis.opsForHash().delete(key, oldest.id().toString());
                        log.debug("Evicted oldest suggestion {} for tenant limit",
                                oldest.id());
                    });
        } catch (RuntimeException ex) {
            log.debug("Limit enforcement failed: {}", ex.toString());
        }
    }

    private static int severityRank(String severity) {
        return switch (severity) {
            case "HIGH" -> 3;
            case "MODERATE" -> 2;
            default -> 1;
        };
    }
}
