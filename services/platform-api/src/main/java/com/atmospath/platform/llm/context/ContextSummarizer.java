package com.atmospath.platform.llm.context;

import java.util.List;
import java.util.stream.Collectors;

import com.atmospath.platform.llm.LlmClient;
import com.atmospath.platform.llm.Message;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.HardConstraint;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.VrpConstraints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Summarizes older conversation turns into a compact system message so the
 * context window stays within token limits. Uses the LLM when available and
 * falls back to a programmatic summary built from structured context data.
 */
@Service
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
public class ContextSummarizer {

    private static final Logger log = LoggerFactory.getLogger(ContextSummarizer.class);

    private final ObjectProvider<LlmClient> llmProvider;

    public ContextSummarizer(ObjectProvider<LlmClient> llmProvider) {
        this.llmProvider = llmProvider;
    }

    /**
     * Summarize old turns into 2-3 sentences preserving constraint decisions
     * and user preferences. Falls back to programmatic extraction on failure.
     */
    public String summarize(List<ConversationContext.Turn> oldTurns, ConversationContext ctx) {
        if (oldTurns == null || oldTurns.isEmpty()) {
            return "";
        }

        LlmClient llm = llmProvider.getIfAvailable();
        if (llm != null) {
            try {
                String transcript = oldTurns.stream()
                        .map(t -> t.role().name().toLowerCase() + ": " + t.content())
                        .collect(Collectors.joining("\n"));

                String prompt = "Summarize this conversation in 2-3 sentences, "
                        + "preserving all constraint decisions and user preferences.\n\n"
                        + transcript;

                String summary = llm.complete(List.of(
                        Message.system("You are a concise summarizer for a route-planning assistant."),
                        Message.user(prompt)));

                if (summary != null && !summary.isBlank()) {
                    return summary.strip();
                }
            } catch (Exception e) {
                log.warn("LLM summarization failed, using fallback: {}", e.getMessage());
            }
        }

        return buildFallbackSummary(ctx);
    }

    /**
     * Programmatic fallback: extracts key facts from structured context
     * without requiring an LLM call.
     */
    public String buildFallbackSummary(ConversationContext ctx) {
        if (ctx == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder("Previous conversation: ");
        VrpConstraints c = ctx.getCurrentConstraints();

        if (c != null && c.stops() != null && !c.stops().isEmpty()) {
            var origin = c.stops().stream()
                    .filter(s -> "origin".equals(s.type()))
                    .map(s -> s.name())
                    .findFirst();
            var dest = c.stops().stream()
                    .filter(s -> "destination".equals(s.type()))
                    .map(s -> s.name())
                    .findFirst();
            if (origin.isPresent() && dest.isPresent()) {
                sb.append("Route: ").append(origin.get()).append(" → ").append(dest.get()).append(". ");
            }
        }

        if (c != null) {
            List<String> parts = new java.util.ArrayList<>();
            if (c.departure() != null && c.departure().time() != null) {
                parts.add("depart " + c.departure().time());
            }
            if (c.vehicle() != null && c.vehicle().type() != null) {
                parts.add(c.vehicle().type());
            }
            if (c.hardConstraints() != null) {
                for (HardConstraint hc : c.hardConstraints()) {
                    String desc = hc.type();
                    if (hc.target() != null) {
                        desc += " " + hc.target();
                    }
                    parts.add(desc);
                }
            }
            if (!parts.isEmpty()) {
                sb.append("Constraints: ").append(String.join(", ", parts)).append(". ");
            }
        }

        SolutionResult sol = ctx.getLastSolution();
        if (sol != null) {
            sb.append("Last solution: ").append(sol.label() != null ? sol.label() : sol.routeId());
            if (sol.distanceMiles() > 0) {
                sb.append(", ").append(String.format("%.0f miles", sol.distanceMiles()));
            }
            if (sol.durationMinutes() > 0) {
                sb.append(", ").append(String.format("%.0f min", sol.durationMinutes()));
            }
            sb.append(". ");
        }

        String result = sb.toString().strip();
        return result.equals("Previous conversation:") ? "" : result;
    }
}
