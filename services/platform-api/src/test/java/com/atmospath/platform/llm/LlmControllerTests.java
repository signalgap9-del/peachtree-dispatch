package com.atmospath.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class LlmControllerTests {

    private LlmStreamService streamService;
    private LlmTokenBudgetService budgetService;
    private LlmController controller;

    private static final LlmProperties PROPS = new LlmProperties(
            true, "http://localhost:4000", "key", "gpt-4o-mini",
            1024, 0.7, 60_000L, 100_000L, 4096);

    @BeforeEach
    void setUp() {
        streamService = mock(LlmStreamService.class);
        budgetService = mock(LlmTokenBudgetService.class);
        controller = new LlmController(PROPS, streamService, budgetService, Optional.empty());
    }

    @Test
    void chatReturnsSseEmitter() {
        when(budgetService.canConsume(anyString(), anyInt())).thenReturn(true);
        when(streamService.streamChat(any())).thenReturn(new SseEmitter());

        var body = new LlmController.ChatRequestBody(
                List.of(new LlmController.MessageDto("user", "Hello")));

        SseEmitter emitter = controller.chat(body);

        assertThat(emitter).isNotNull();
        verify(budgetService).recordUsage(anyString(), anyInt());
    }

    @Test
    void chatRejectsEmptyMessages() {
        var body = new LlmController.ChatRequestBody(List.of());

        assertThatThrownBy(() -> controller.chat(body))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("messages must not be empty");
    }

    @Test
    void chatRejectsNullMessages() {
        var body = new LlmController.ChatRequestBody(null);

        assertThatThrownBy(() -> controller.chat(body))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void chatRejectsWhenBudgetExceeded() {
        when(budgetService.canConsume(anyString(), anyInt())).thenReturn(false);

        var body = new LlmController.ChatRequestBody(
                List.of(new LlmController.MessageDto("user", "Hello")));

        assertThatThrownBy(() -> controller.chat(body))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("budget exceeded");
    }

    @Test
    void statusReturnsBudgetInfo() {
        when(budgetService.getStatus("default"))
                .thenReturn(new LlmTokenBudgetService.TokenBudgetStatus(5000, 95000, 100000));

        Map<String, Object> status = controller.status();

        assertThat(status.get("enabled")).isEqualTo(true);
        assertThat(status.get("model")).isEqualTo("gpt-4o-mini");
        assertThat(status.get("dailyBudgetUsed")).isEqualTo(5000L);
        assertThat(status.get("dailyBudgetRemaining")).isEqualTo(95000L);
    }

    @Test
    void chatWithDisabledLlmThrows() {
        LlmProperties disabled = new LlmProperties(
                false, null, null, null, 0, 0, 0, 0, 0);
        var disabledController = new LlmController(
                disabled, streamService, budgetService, Optional.empty());

        var body = new LlmController.ChatRequestBody(
                List.of(new LlmController.MessageDto("user", "Hello")));

        assertThatThrownBy(() -> disabledController.chat(body))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not enabled");
    }

    @Test
    void chatWithSanitizerAppliesSanitization() {
        LlmController.LlmInputSanitizer sanitizer = input -> input.replace("bad", "good");
        var sanitizedController = new LlmController(
                PROPS, streamService, budgetService, Optional.of(sanitizer));
        when(budgetService.canConsume(anyString(), anyInt())).thenReturn(true);
        when(streamService.streamChat(any())).thenReturn(new SseEmitter());

        var body = new LlmController.ChatRequestBody(
                List.of(new LlmController.MessageDto("user", "bad word")));

        SseEmitter emitter = sanitizedController.chat(body);
        assertThat(emitter).isNotNull();
    }
}
