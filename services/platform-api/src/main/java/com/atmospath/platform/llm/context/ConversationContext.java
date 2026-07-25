package com.atmospath.platform.llm.context;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.atmospath.platform.llm.Message;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.VrpConstraints;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Mutable state object for a single conversation session. Stored in Redis
 * as JSON; each access refreshes the TTL.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConversationContext {

    private String sessionId;
    private List<Turn> turns = new ArrayList<>();
    private VrpConstraints currentConstraints;
    private SolutionResult lastSolution;
    private Instant createdAt;
    private Instant lastActiveAt;

    /** A single conversational exchange with optional intent and metadata. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Turn(
            Message.Role role,
            String content,
            Instant timestamp,
            String intent,
            Map<String, String> metadata) {

        public Turn {
            metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        }
    }

    public ConversationContext() {
    }

    public ConversationContext(String sessionId) {
        this.sessionId = sessionId;
        this.createdAt = Instant.now();
        this.lastActiveAt = this.createdAt;
    }

    public void addTurn(Turn turn) {
        turns.add(turn);
        lastActiveAt = Instant.now();
    }

    /** Merge new constraints into the current set (replace whole object). */
    public void updateConstraints(VrpConstraints newConstraints) {
        this.currentConstraints = newConstraints;
        lastActiveAt = Instant.now();
    }

    /** Apply a partial diff to the current constraints. */
    public void applyConstraintDiff(ConstraintDiff diff) {
        this.currentConstraints = diff.applyTo(this.currentConstraints);
        lastActiveAt = Instant.now();
    }

    /** Returns the last {@code maxTurns} turns mapped to LLM messages. */
    public List<Message> toLlmMessages(int maxTurns) {
        int from = Math.max(0, turns.size() - maxTurns);
        return turns.subList(from, turns.size()).stream()
                .map(t -> new Message(t.role(), t.content()))
                .toList();
    }

    public boolean isExpired(Duration ttl) {
        return Duration.between(lastActiveAt, Instant.now()).compareTo(ttl) > 0;
    }

    // --- Getters and setters for Jackson serialization ---

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public List<Turn> getTurns() { return turns; }
    public void setTurns(List<Turn> turns) { this.turns = turns != null ? new ArrayList<>(turns) : new ArrayList<>(); }

    public VrpConstraints getCurrentConstraints() { return currentConstraints; }
    public void setCurrentConstraints(VrpConstraints c) { this.currentConstraints = c; }

    public SolutionResult getLastSolution() { return lastSolution; }
    public void setLastSolution(SolutionResult s) { this.lastSolution = s; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant t) { this.createdAt = t; }

    public Instant getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(Instant t) { this.lastActiveAt = t; }
}
