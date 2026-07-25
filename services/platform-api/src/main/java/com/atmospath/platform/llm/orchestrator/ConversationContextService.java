package com.atmospath.platform.llm.orchestrator;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * In-memory conversation context store. Phase 2D will replace the
 * backing store with Redis/DynamoDB and add TTL eviction; the public
 * API stays the same so the orchestrator does not change.
 */
@Service
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
public class ConversationContextService {

    private static final Logger log = LoggerFactory.getLogger(ConversationContextService.class);

    private final Map<String, ConversationContext> sessions = new ConcurrentHashMap<>();

    public ConversationContext load(String sessionId) {
        return sessions.getOrDefault(sessionId, ConversationContext.empty(sessionId));
    }

    public void save(ConversationContext context) {
        sessions.put(context.sessionId(), context);
        log.debug("Saved conversation context: session={} intent={}",
                context.sessionId(), context.lastIntent());
    }

    public ConversationContext update(String sessionId, String intent,
                                      Map<String, Object> constraints,
                                      SolutionResult solution,
                                      List<RouteAlternative> alternatives,
                                      String userMessage) {
        var context = new ConversationContext(
                sessionId, intent, constraints, solution, alternatives, userMessage, Instant.now());
        save(context);
        return context;
    }

    public void evict(String sessionId) {
        sessions.remove(sessionId);
    }
}
