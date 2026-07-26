package com.atmospath.platform.llm.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.atmospath.platform.llm.LlmClient;
import com.atmospath.platform.llm.Message;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.StopConstraint;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.TimeConstraint;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.VrpConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ConversationContextServiceTests {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final LlmClient llmClient = mock(LlmClient.class);
    private final ContextSummarizer summarizer = mock(ContextSummarizer.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private ConversationContextService service;

    @SuppressWarnings("unchecked")
    private ObjectProvider<StringRedisTemplate> redisProvider() {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<LlmClient> llmProvider() {
        ObjectProvider<LlmClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(llmClient);
        return provider;
    }

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new ConversationContextService(redisProvider(), llmProvider(), summarizer, objectMapper);
    }

    @Test
    void createSaveRetrieveRoundTrip() {
        // First call: no existing data
        when(valueOps.get(anyString())).thenReturn(null);

        ConversationContext ctx = service.getOrCreate("sess-1");
        assertThat(ctx.getSessionId()).isEqualTo("sess-1");
        assertThat(ctx.getTurns()).isEmpty();

        // Add a turn and save
        ctx.addTurn(new ConversationContext.Turn(
                Message.Role.USER, "Plan Seattle to Miami", Instant.now(), "route_plan", Map.of()));
        service.save(ctx);

        // Verify Redis write happened
        verify(valueOps, times(2)).set(eq("llm:context:sess-1"), anyString(), eq(Duration.ofHours(2)));
    }

    @Test
    void retrieveExistingContext() throws Exception {
        ConversationContext original = new ConversationContext("sess-2");
        original.addTurn(new ConversationContext.Turn(
                Message.Role.USER, "Hello", Instant.now(), null, Map.of()));
        String json = objectMapper.writeValueAsString(original);
        when(valueOps.get("llm:context:sess-2")).thenReturn(json);

        ConversationContext retrieved = service.getOrCreate("sess-2");

        assertThat(retrieved.getSessionId()).isEqualTo("sess-2");
        assertThat(retrieved.getTurns()).hasSize(1);
        assertThat(retrieved.getTurns().get(0).content()).isEqualTo("Hello");
    }

    @Test
    void updateConstraintsMergesCorrectly() {
        when(valueOps.get(anyString())).thenReturn(null);
        ConversationContext ctx = service.getOrCreate("sess-3");

        VrpConstraints constraints = new VrpConstraints(
                List.of(new StopConstraint("Denver", "origin", null, 0)),
                null, new TimeConstraint("08:00", null), null, List.of(), List.of(), null);
        ctx.updateConstraints(constraints);

        assertThat(ctx.getCurrentConstraints()).isNotNull();
        assertThat(ctx.getCurrentConstraints().departure().time()).isEqualTo("08:00");
    }

    @Test
    void applyDiffOnlyChangesSpecifiedFields() {
        when(valueOps.get(anyString())).thenReturn(null);
        ConversationContext ctx = service.getOrCreate("sess-4");

        VrpConstraints base = new VrpConstraints(
                List.of(new StopConstraint("Seattle", "origin", null, 0)),
                null, new TimeConstraint("08:00", "strict"), null, List.of(), List.of(), "minimize_risk");
        ctx.updateConstraints(base);

        ConstraintDiff diff = new ConstraintDiff(Map.of("departure.time", "10:00"), List.of(), List.of());
        ctx.applyConstraintDiff(diff);

        assertThat(ctx.getCurrentConstraints().departure().time()).isEqualTo("10:00");
        assertThat(ctx.getCurrentConstraints().departure().flexibility()).isEqualTo("strict");
        assertThat(ctx.getCurrentConstraints().objective()).isEqualTo("minimize_risk");
    }

    @Test
    void contextWindowSummarizesWhenExceedingMaxTokens() {
        when(valueOps.get(anyString())).thenReturn(null);
        ConversationContext ctx = service.getOrCreate("sess-5");

        // Add 20 turns with enough content to exceed a small token budget
        for (int i = 0; i < 20; i++) {
            ctx.addTurn(new ConversationContext.Turn(
                    i % 2 == 0 ? Message.Role.USER : Message.Role.ASSISTANT,
                    "Message number " + i + " with some extra content to fill tokens",
                    Instant.now(), null, Map.of()));
        }

        when(summarizer.summarize(anyList(), any())).thenReturn("Previous: user planned a route.");

        List<Message> window = service.buildContextWindow(ctx, 50);

        // Should have 1 summary + 5 verbatim = 6 messages
        assertThat(window).hasSize(6);
        assertThat(window.get(0).role()).isEqualTo(Message.Role.SYSTEM);
        assertThat(window.get(0).content()).contains("Previous");
    }

    @Test
    void contextWindowKeepsAllWhenWithinBudget() {
        when(valueOps.get(anyString())).thenReturn(null);
        ConversationContext ctx = service.getOrCreate("sess-6");

        ctx.addTurn(new ConversationContext.Turn(Message.Role.USER, "Hi", Instant.now(), null, Map.of()));
        ctx.addTurn(new ConversationContext.Turn(Message.Role.ASSISTANT, "Hello!", Instant.now(), null, Map.of()));

        List<Message> window = service.buildContextWindow(ctx, 10_000);

        assertThat(window).hasSize(2);
    }

    @Test
    void expiredContextReturnsNewEmpty() throws Exception {
        ConversationContext expired = new ConversationContext("sess-7");
        expired.setLastActiveAt(Instant.now().minus(Duration.ofHours(3)));
        String json = objectMapper.writeValueAsString(expired);
        when(valueOps.get("llm:context:sess-7")).thenReturn(json);

        ConversationContext ctx = service.getOrCreate("sess-7");

        assertThat(ctx.getSessionId()).isEqualTo("sess-7");
        assertThat(ctx.getTurns()).isEmpty();
        assertThat(ctx.getCreatedAt()).isAfter(Instant.now().minusSeconds(5));
    }

    @Test
    void redisUnavailableFallsBackToInMemory() {
        // Create a service with no Redis
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> noRedis = mock(ObjectProvider.class);
        when(noRedis.getIfAvailable()).thenReturn(null);

        var localService = new ConversationContextService(noRedis, llmProvider(), summarizer, objectMapper);

        ConversationContext ctx = localService.getOrCreate("sess-8");
        ctx.addTurn(new ConversationContext.Turn(
                Message.Role.USER, "Test offline", Instant.now(), null, Map.of()));
        localService.save(ctx);

        // Retrieve again - should come from in-memory store
        ConversationContext retrieved = localService.getOrCreate("sess-8");
        assertThat(retrieved.getTurns()).hasSize(1);
        assertThat(retrieved.getTurns().get(0).content()).isEqualTo("Test offline");
    }

    @Test
    void detectDiffKeywordFallbackAvoid() {
        when(valueOps.get(anyString())).thenReturn(null);
        ConversationContext ctx = service.getOrCreate("sess-9");

        // LLM fails → keyword fallback
        when(llmClient.complete(anyList())).thenThrow(new IllegalStateException("unavailable"));

        ConstraintDiff diff = service.detectDiff("Can you avoid I-5?", ctx);

        assertThat(diff.addedConstraints()).hasSize(1);
        assertThat(diff.addedConstraints().get(0)).contains("avoid_corridor");
    }

    @Test
    void detectDiffKeywordFallbackRemove() {
        when(valueOps.get(anyString())).thenReturn(null);
        ConversationContext ctx = service.getOrCreate("sess-10");

        when(llmClient.complete(anyList())).thenThrow(new IllegalStateException("unavailable"));

        ConstraintDiff diff = service.detectDiff("remove the hazmat restriction", ctx);

        assertThat(diff.removedConstraints()).isNotEmpty();
    }

    @Test
    void deleteRemovesFromBothStores() {
        when(valueOps.get(anyString())).thenReturn(null);
        ConversationContext ctx = service.getOrCreate("sess-11");
        service.save(ctx);

        service.delete("sess-11");

        verify(redis).delete("llm:context:sess-11");
    }
}
