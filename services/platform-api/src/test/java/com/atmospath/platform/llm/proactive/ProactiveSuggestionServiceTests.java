package com.atmospath.platform.llm.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.atmospath.platform.risk.AlertStreamService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class ProactiveSuggestionServiceTests {

    private StringRedisTemplate redis;
    private HashOperations<String, Object, Object> hashOps;
    private AlertStreamService alertStream;
    private ProactiveSuggestionService service;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private static final UUID TENANT_ID = UUID.fromString("cccccccc-0000-0000-0000-000000000003");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        hashOps = mock(HashOperations.class);
        alertStream = mock(AlertStreamService.class);

        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        when(redis.opsForHash()).thenReturn(hashOps);

        ProactiveProperties props = new ProactiveProperties(true, 300_000, 60, 10);
        service = new ProactiveSuggestionService(redisProvider, alertStream, props, objectMapper);
    }

    private RiskSuggestion suggestion(String name, String severity) {
        return new RiskSuggestion(UUID.randomUUID(), TENANT_ID,
                UUID.randomUUID(), name, severity,
                "Test suggestion for " + name, 75, 20,
                "I-84 East (+35 min)", Instant.now(), false);
    }

    @Test
    void publishAppearsInPendingList() throws Exception {
        RiskSuggestion s = suggestion("Seattle to Portland", "HIGH");
        when(hashOps.size(anyString())).thenReturn(0L);
        when(hashOps.entries(anyString())).thenReturn(Map.of(
                s.id().toString(), (Object) objectMapper.writeValueAsString(s)));

        service.publishSuggestion(s);
        List<RiskSuggestion> pending = service.getPendingSuggestions(TENANT_ID);

        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).id()).isEqualTo(s.id());
        assertThat(pending.get(0).routeName()).isEqualTo("Seattle to Portland");
        verify(hashOps).put(eq("llm:suggestions:" + TENANT_ID),
                eq(s.id().toString()), anyString());
    }

    @Test
    void acknowledgeRemovesFromPending() throws Exception {
        RiskSuggestion s = suggestion("Route A", "MODERATE");
        RiskSuggestion acked = s.withAcknowledged(true);

        when(hashOps.get(anyString(), eq(s.id().toString())))
                .thenReturn(objectMapper.writeValueAsString(s));
        when(hashOps.entries(anyString())).thenReturn(Map.of(
                s.id().toString(), (Object) objectMapper.writeValueAsString(acked)));

        service.acknowledgeSuggestion(TENANT_ID, s.id());
        List<RiskSuggestion> pending = service.getPendingSuggestions(TENANT_ID);

        assertThat(pending).isEmpty();
        verify(hashOps).put(anyString(), eq(s.id().toString()), anyString());
    }

    @Test
    void dismissRemovesFromStorage() {
        RiskSuggestion s = suggestion("Route B", "HIGH");

        service.dismissSuggestion(TENANT_ID, s.id());

        verify(hashOps).delete("llm:suggestions:" + TENANT_ID, s.id().toString());
    }

    @Test
    void publishSetsTtlOnRedisKey() {
        RiskSuggestion s = suggestion("Route C", "MODERATE");
        when(hashOps.size(anyString())).thenReturn(0L);

        service.publishSuggestion(s);

        verify(redis).expire(eq("llm:suggestions:" + TENANT_ID), eq(Duration.ofHours(24)));
    }

    @Test
    void maxSuggestionsLimitEvictsOldest() throws Exception {
        // Fill to the limit (10)
        when(hashOps.size(anyString())).thenReturn(10L);

        Instant old = Instant.now().minusSeconds(3600);
        RiskSuggestion oldest = new RiskSuggestion(UUID.randomUUID(), TENANT_ID,
                UUID.randomUUID(), "Oldest Route", "LOW", "old", 50, 20,
                "alt", old, false);

        Map<Object, Object> existing = new HashMap<>();
        existing.put(oldest.id().toString(), objectMapper.writeValueAsString(oldest));
        for (int i = 0; i < 9; i++) {
            RiskSuggestion s = suggestion("Route " + i, "MODERATE");
            existing.put(s.id().toString(), objectMapper.writeValueAsString(s));
        }
        when(hashOps.entries(anyString())).thenReturn(existing);

        RiskSuggestion newSuggestion = suggestion("New Route", "HIGH");
        service.publishSuggestion(newSuggestion);

        // Oldest should have been evicted
        verify(hashOps).delete(anyString(), eq(oldest.id().toString()));
        // New one should be stored
        verify(hashOps).put(anyString(), eq(newSuggestion.id().toString()), anyString());
    }

    @Test
    void publishPushesToSseStream() {
        RiskSuggestion s = suggestion("SSE Route", "HIGH");
        when(hashOps.size(anyString())).thenReturn(0L);

        service.publishSuggestion(s);

        verify(alertStream).broadcastEvent(eq("risk_suggestion"), anyString());
    }
}
