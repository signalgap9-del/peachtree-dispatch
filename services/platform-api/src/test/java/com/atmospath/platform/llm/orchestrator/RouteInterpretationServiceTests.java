package com.atmospath.platform.llm.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.atmospath.platform.llm.LiteLlmClient;
import com.atmospath.platform.llm.LlmProperties;
import com.atmospath.platform.llm.LlmStreamService;
import com.atmospath.platform.llm.Message;
import com.atmospath.platform.llm.prompt.PromptTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RouteInterpretationServiceTests {

    private final PromptTemplateService templates = new PromptTemplateService();
    private final LiteLlmClient llmClient = mock(LiteLlmClient.class);
    private final LlmStreamService streamService = mock(LlmStreamService.class);

    private static final LlmProperties PROPS = new LlmProperties(
            true, "http://localhost:4000", "test-key", "gpt-4o-mini",
            1024, 0.7, 60_000L, 100_000L, 4096);

    private RouteInterpretationService service;

    private static final SolutionResult SAMPLE_SOLUTION = new SolutionResult(
            "Denver", "Boulder", 42.0, 55, 72,
            List.of(
                    new RouteSegment("I-25 North", 20.0, 25, 45, List.of()),
                    new RouteSegment("US-36 West", 12.0, 18, 60, List.of("Wind advisory")),
                    new RouteSegment("28th Street", 10.0, 12, 85, List.of("Flood warning", "Road closure"))),
            List.of("Flood warning on 28th Street", "Wind advisory on US-36"));

    private static final List<RouteAlternative> SAMPLE_ALTERNATIVES = List.of(
            new RouteAlternative("A", "North route via I-25", 42.0, 55, 72,
                    List.of("Flood warning"), List.of()),
            new RouteAlternative("B", "South route via C-470", 58.0, 95, 23,
                    List.of(), List.of()));

    @BeforeEach
    void setUp() {
        service = new RouteInterpretationService(
                templates, llmClient, streamService, PROPS);
    }

    @Test
    void structuredFallbackIncludesKeyDataPoints() {
        String fallback = service.buildStructuredFallback(SAMPLE_SOLUTION);

        assertThat(fallback).contains("Denver");
        assertThat(fallback).contains("Boulder");
        assertThat(fallback).contains("42.0 miles");
        assertThat(fallback).contains("55 min");
        assertThat(fallback).contains("72/100");
        assertThat(fallback).contains("High");
        assertThat(fallback).contains("2 active alert(s)");
    }

    @Test
    void structuredFallbackIncludesSegmentDetail() {
        String fallback = service.buildStructuredFallback(SAMPLE_SOLUTION);

        assertThat(fallback).contains("Segment 1 (I-25 North)");
        assertThat(fallback).contains("Segment 3 (28th Street)");
        assertThat(fallback).contains("Flood warning");
        assertThat(fallback).contains("Road closure");
    }

    @Test
    void comparisonFallbackShowsTradeOff() {
        String fallback = service.buildComparisonFallback(SAMPLE_ALTERNATIVES);

        assertThat(fallback).contains("Option A");
        assertThat(fallback).contains("Option B");
        assertThat(fallback).contains("72/100 (High)");
        assertThat(fallback).contains("23/100 (Low)");
        // Trade-off: B is 40 min longer but risk drops from 72 to 23
        assertThat(fallback).contains("Trade-off");
        assertThat(fallback).contains("40 min longer");
        assertThat(fallback).contains("risk drops from 72 to 23");
    }

    @Test
    void buildRouteMessagesRendersTemplate() {
        List<Message> messages = service.buildRouteMessages(SAMPLE_SOLUTION, "en");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo(Message.Role.SYSTEM);
        assertThat(messages.get(1).role()).isEqualTo(Message.Role.USER);
        // User message should contain the route data
        assertThat(messages.get(1).content()).contains("Denver");
        assertThat(messages.get(1).content()).contains("Boulder");
        assertThat(messages.get(1).content()).contains("72/100");
    }

    @Test
    void koreanLanguageAddsKoreanDirective() {
        List<Message> messages = service.buildRouteMessages(SAMPLE_SOLUTION, "ko");

        assertThat(messages.get(0).content()).contains("한국어로 응답하세요");
    }

    @Test
    void englishLanguageDoesNotAddKoreanDirective() {
        List<Message> messages = service.buildRouteMessages(SAMPLE_SOLUTION, "en");

        assertThat(messages.get(0).content()).doesNotContain("한국어로 응답하세요");
    }

    @Test
    void containsKoreanDetectsHangul() {
        assertThat(RouteInterpretationService.containsKorean("오늘 날씨 어때?")).isTrue();
        assertThat(RouteInterpretationService.containsKorean("What's the weather?")).isFalse();
        assertThat(RouteInterpretationService.containsKorean("경로 안내")).isTrue();
        assertThat(RouteInterpretationService.containsKorean(null)).isFalse();
        assertThat(RouteInterpretationService.containsKorean("")).isFalse();
    }

    @Test
    void comparisonMessagesIncludeAllAlternatives() {
        List<Message> messages = service.buildComparisonMessages(SAMPLE_ALTERNATIVES, "en");

        assertThat(messages).hasSize(2);
        String userContent = messages.get(1).content();
        assertThat(userContent).contains("Option A");
        assertThat(userContent).contains("Option B");
        assertThat(userContent).contains("North route via I-25");
        assertThat(userContent).contains("South route via C-470");
    }

    @Test
    void structuredFallbackForEmptySolution() {
        var empty = new SolutionResult("A", "B", 0, 0, 50, List.of(), List.of());
        String fallback = service.buildStructuredFallback(empty);

        assertThat(fallback).contains("A");
        assertThat(fallback).contains("B");
        assertThat(fallback).contains("0 active alert(s)");
    }

    @Test
    void comparisonFallbackWithSingleAlternativeHasNoTradeOff() {
        var single = List.of(
                new RouteAlternative("A", "Only route", 42.0, 55, 50, List.of(), List.of()));
        String fallback = service.buildComparisonFallback(single);

        assertThat(fallback).contains("Option A");
        assertThat(fallback).doesNotContain("Trade-off");
    }
}
