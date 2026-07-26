package com.atmospath.platform.llm.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.atmospath.platform.llm.LiteLlmClient;
import com.atmospath.platform.llm.LlmProperties;
import com.atmospath.platform.llm.LlmStreamService;
import com.atmospath.platform.llm.Message;
import com.atmospath.platform.llm.prompt.PromptTemplateService;
import com.atmospath.platform.llm.rag.RagService;
import com.atmospath.platform.llm.rag.SourceTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class RouteInterpretationServiceTests {

    private RouteInterpretationService service;

    private static final LlmProperties PROPS = new LlmProperties(
            true, "http://localhost:4000", "key", "gpt-4o-mini",
            1024, 0.7, 60_000L, 100_000L, 4096);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<RagService> ragProvider = mock(ObjectProvider.class);
        ObjectProvider<SourceTracker> trackerProvider = mock(ObjectProvider.class);
        when(ragProvider.getIfAvailable()).thenReturn(null);
        when(trackerProvider.getIfAvailable()).thenReturn(null);

        service = new RouteInterpretationService(
                new PromptTemplateService(),
                mock(LiteLlmClient.class),
                mock(LlmStreamService.class),
                PROPS, ragProvider, trackerProvider);
    }

    private SolutionResult sampleSolution() {
        return new SolutionResult(
                "Denver", "Boulder", 42.0, 55, 72,
                List.of(
                        new RouteSegment("I-25 N", 30.0, 35, 65, List.of("Wind Advisory")),
                        new RouteSegment("US-36 W", 12.0, 20, 45, List.of())),
                List.of("Wind Advisory", "Road Construction"));
    }

    @Test
    void buildStructuredFallbackFormatsRouteSummary() {
        String fallback = service.buildStructuredFallback(sampleSolution());

        assertThat(fallback).contains("Denver to Boulder");
        assertThat(fallback).contains("42.0 miles");
        assertThat(fallback).contains("55 min");
        assertThat(fallback).contains("72/100 (High)");
        assertThat(fallback).contains("2 active alert(s)");
        assertThat(fallback).contains("Segment 1 (I-25 N)");
        assertThat(fallback).contains("Wind Advisory");
    }

    @Test
    void buildStructuredFallbackHandlesNoAlerts() {
        SolutionResult solution = new SolutionResult(
                "Seattle", "Portland", 175.0, 180, 25,
                List.of(new RouteSegment("I-5 S", 175.0, 180, 25, List.of())),
                List.of());

        String fallback = service.buildStructuredFallback(solution);

        assertThat(fallback).contains("25/100 (Low)");
        assertThat(fallback).contains("0 active alert(s)");
    }

    @Test
    void buildComparisonFallbackFormatsAlternatives() {
        List<RouteAlternative> alternatives = List.of(
                new RouteAlternative("A", "Fastest route", 100.0, 90, 75,
                        List.of("Flood Warning"), List.of()),
                new RouteAlternative("B", "Safer route", 120.0, 110, 35,
                        List.of(), List.of()));

        String fallback = service.buildComparisonFallback(alternatives);

        assertThat(fallback).contains("Route Comparison");
        assertThat(fallback).contains("Option A");
        assertThat(fallback).contains("Option B");
        assertThat(fallback).contains("Trade-off");
        assertThat(fallback).contains("20 min longer");
    }

    @Test
    void buildComparisonFallbackHandlesSingleAlternative() {
        List<RouteAlternative> alternatives = List.of(
                new RouteAlternative("A", "Only route", 50.0, 45, 30, List.of(), List.of()));

        String fallback = service.buildComparisonFallback(alternatives);

        assertThat(fallback).contains("Option A");
        assertThat(fallback).doesNotContain("Trade-off");
    }

    @Test
    void buildRouteMessagesRendersTemplate() {
        List<Message> messages = service.buildRouteMessages(sampleSolution(), "en");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo(Message.Role.SYSTEM);
        assertThat(messages.get(1).role()).isEqualTo(Message.Role.USER);
        assertThat(messages.get(1).content()).contains("Denver");
        assertThat(messages.get(1).content()).contains("Boulder");
    }

    @Test
    void buildRouteMessagesAddsKoreanDirective() {
        List<Message> messages = service.buildRouteMessages(sampleSolution(), "ko");

        assertThat(messages.get(0).content()).contains("Korean");
    }

    @Test
    void buildComparisonMessagesRendersTemplate() {
        List<RouteAlternative> alternatives = List.of(
                new RouteAlternative("A", "Fastest", 100.0, 90, 50, List.of(), List.of()),
                new RouteAlternative("B", "Safest", 110.0, 100, 30, List.of(), List.of()));

        List<Message> messages = service.buildComparisonMessages(alternatives, "en");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).content()).contains("Option A");
        assertThat(messages.get(1).content()).contains("Option B");
    }

    @Test
    void containsKoreanDetectsHangul() {
        assertThat(RouteInterpretationService.containsKorean("서울에서 부산까지")).isTrue();
        assertThat(RouteInterpretationService.containsKorean("Seattle to Portland")).isFalse();
        assertThat(RouteInterpretationService.containsKorean(null)).isFalse();
        assertThat(RouteInterpretationService.containsKorean("")).isFalse();
    }

    @Test
    void riskLevelClassification() {
        assertThat(new SolutionResult("A", "B", 0, 0, 85, null, null).riskLevel()).isEqualTo("High");
        assertThat(new SolutionResult("A", "B", 0, 0, 55, null, null).riskLevel()).isEqualTo("Moderate");
        assertThat(new SolutionResult("A", "B", 0, 0, 20, null, null).riskLevel()).isEqualTo("Low");
    }

    @Test
    void segmentHasAlertsCheck() {
        assertThat(new RouteSegment("I-5", 10, 15, 50, List.of("Flood")).hasAlerts()).isTrue();
        assertThat(new RouteSegment("I-5", 10, 15, 50, null).hasAlerts()).isFalse();
    }

    @Test
    void alternativeRiskLevel() {
        assertThat(new RouteAlternative("A", "d", 0, 0, 80, null, null).riskLevel()).isEqualTo("High");
        assertThat(new RouteAlternative("A", "d", 0, 0, 50, null, null).riskLevel()).isEqualTo("Moderate");
        assertThat(new RouteAlternative("A", "d", 0, 0, 10, null, null).riskLevel()).isEqualTo("Low");
    }

    @Test
    void comparisonFallbackShorterButRiskier() {
        List<RouteAlternative> alternatives = List.of(
                new RouteAlternative("A", "Safe", 120.0, 110, 35, List.of(), List.of()),
                new RouteAlternative("B", "Fast", 100.0, 90, 75, List.of(), List.of()));

        String fallback = service.buildComparisonFallback(alternatives);

        assertThat(fallback).contains("20 min shorter");
        assertThat(fallback).contains("risk rises");
    }
}
