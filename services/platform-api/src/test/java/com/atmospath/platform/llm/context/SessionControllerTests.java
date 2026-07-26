package com.atmospath.platform.llm.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SessionControllerTests {

    private ConversationContextService contextService;
    private SessionController controller;

    @BeforeEach
    void setUp() {
        contextService = mock(ConversationContextService.class);
        controller = new SessionController(contextService);
    }

    @Test
    void createSessionReturnsId() {
        when(contextService.getOrCreate(anyString())).thenAnswer(inv ->
                new ConversationContext(inv.getArgument(0)));

        Map<String, String> result = controller.createSession();

        assertThat(result).containsKey("sessionId");
        assertThat(result.get("sessionId")).isNotBlank();
    }

    @Test
    void getSessionReturnsMetadata() {
        ConversationContext ctx = new ConversationContext("test-session");
        when(contextService.getOrCreate("test-session")).thenReturn(ctx);

        Map<String, Object> result = controller.getSession("test-session");

        assertThat(result.get("sessionId")).isEqualTo("test-session");
        assertThat(result.get("turnCount")).isEqualTo(0);
        assertThat(result.get("hasConstraints")).isEqualTo(false);
    }

    @Test
    void deleteSessionDelegatesToService() {
        controller.deleteSession("sess-1");

        verify(contextService).delete("sess-1");
    }

    @Test
    void getConstraintsThrowsWhenNoneSet() {
        ConversationContext ctx = new ConversationContext("sess-2");
        when(contextService.getOrCreate("sess-2")).thenReturn(ctx);

        assertThatThrownBy(() -> controller.getConstraints("sess-2"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No constraints");
    }
}
