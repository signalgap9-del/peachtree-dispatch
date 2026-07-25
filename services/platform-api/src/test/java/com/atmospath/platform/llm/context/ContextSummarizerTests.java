package com.atmospath.platform.llm.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.atmospath.platform.llm.LlmClient;
import com.atmospath.platform.llm.Message;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.HardConstraint;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.StopConstraint;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.TimeConstraint;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.VehicleConstraint;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.VrpConstraints;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ContextSummarizerTests {

    private final LlmClient llmClient = mock(LlmClient.class);

    @SuppressWarnings("unchecked")
    private ContextSummarizer withLlm() {
        ObjectProvider<LlmClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(llmClient);
        return new ContextSummarizer(provider);
    }

    @SuppressWarnings("unchecked")
    private ContextSummarizer withoutLlm() {
        ObjectProvider<LlmClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return new ContextSummarizer(provider);
    }

    private ConversationContext buildContextWithTurns(int turnCount) {
        ConversationContext ctx = new ConversationContext("test-session");
        ctx.updateConstraints(new VrpConstraints(
                List.of(new StopConstraint("Seattle", "origin", null, 0),
                        new StopConstraint("Miami", "destination", null, 0)),
                new VehicleConstraint("truck", false, null),
                new TimeConstraint("08:00", "strict"),
                null,
                List.of(),
                List.of(new HardConstraint("avoid_corridor", "I-5", "user request")),
                "minimize_risk"));
        ctx.setLastSolution(new SolutionResult(
                "route-b", "Route B (lower risk)", 42.0, 55.0, "low",
                List.of("Seattle", "Portland", "Miami"), Map.of()));

        for (int i = 0; i < turnCount; i++) {
            ctx.addTurn(new ConversationContext.Turn(
                    i % 2 == 0 ? Message.Role.USER : Message.Role.ASSISTANT,
                    "Turn " + i + " content about route planning",
                    Instant.now(), null, Map.of()));
        }
        return ctx;
    }

    @Test
    void llmSummarizesOldTurns() {
        ContextSummarizer summarizer = withLlm();
        when(llmClient.complete(anyList())).thenReturn(
                "User planned Seattle to Miami route, chose lower-risk option, asked about 2hr delay.");

        ConversationContext ctx = buildContextWithTurns(10);
        List<ConversationContext.Turn> oldTurns = ctx.getTurns().subList(0, 5);

        String summary = summarizer.summarize(oldTurns, ctx);

        assertThat(summary).contains("Seattle");
        assertThat(summary).contains("lower-risk");
    }

    @Test
    void llmFailureFallsBackToProgrammaticSummary() {
        ContextSummarizer summarizer = withLlm();
        when(llmClient.complete(anyList())).thenThrow(new IllegalStateException("Bedrock down"));

        ConversationContext ctx = buildContextWithTurns(10);
        List<ConversationContext.Turn> oldTurns = ctx.getTurns().subList(0, 5);

        String summary = summarizer.summarize(oldTurns, ctx);

        assertThat(summary).contains("Seattle");
        assertThat(summary).contains("Miami");
        assertThat(summary).contains("08:00");
        assertThat(summary).contains("truck");
        assertThat(summary).contains("avoid_corridor I-5");
        assertThat(summary).contains("Route B");
    }

    @Test
    void noLlmAvailableUsesFallbackDirectly() {
        ContextSummarizer summarizer = withoutLlm();
        ConversationContext ctx = buildContextWithTurns(3);

        String summary = summarizer.summarize(ctx.getTurns(), ctx);

        assertThat(summary).contains("Seattle");
        assertThat(summary).contains("Miami");
    }

    @Test
    void emptyTurnsReturnsEmptySummary() {
        ContextSummarizer summarizer = withLlm();
        ConversationContext ctx = new ConversationContext("empty");

        String summary = summarizer.summarize(List.of(), ctx);

        assertThat(summary).isEmpty();
    }

    @Test
    void nullTurnsReturnsEmptySummary() {
        ContextSummarizer summarizer = withLlm();
        ConversationContext ctx = new ConversationContext("null-turns");

        String summary = summarizer.summarize(null, ctx);

        assertThat(summary).isEmpty();
    }

    @Test
    void fallbackSummaryWithNoConstraintsOrSolution() {
        ContextSummarizer summarizer = withoutLlm();
        ConversationContext ctx = new ConversationContext("bare");

        String summary = summarizer.buildFallbackSummary(ctx);

        assertThat(summary).isEmpty();
    }

    @Test
    void fallbackSummaryPreservesKeyDecisions() {
        ContextSummarizer summarizer = withoutLlm();
        ConversationContext ctx = buildContextWithTurns(5);

        String summary = summarizer.buildFallbackSummary(ctx);

        assertThat(summary).contains("Route: Seattle → Miami");
        assertThat(summary).contains("depart 08:00");
        assertThat(summary).contains("42 miles");
        assertThat(summary).contains("55 min");
    }
}
