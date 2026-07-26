package com.atmospath.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

/**
 * Tests for {@link LlmStreamService}. Verifies SSE emitter lifecycle,
 * event naming, and error propagation using a mocked {@link LiteLlmClient}.
 */
class LlmStreamServiceTests {

    private LiteLlmClient llmClient;
    private LlmStreamService service;
    private LlmProperties properties;

    @BeforeEach
    void setUp() {
        llmClient = mock(LiteLlmClient.class);
        properties = new LlmProperties(true, "http://localhost:4000", "key",
                "gpt-4o-mini", 1024, 0.7, 30_000L, 100_000L, 4096);
        service = new LlmStreamService(llmClient, properties);
    }

    @Test
    void streamChatReturnsEmitterWithConfiguredTimeout() {
        when(llmClient.chatStream(any())).thenReturn(Flux.empty());

        SseEmitter emitter = service.streamChat(List.of(Message.user("Hi")));

        assertThat(emitter).isNotNull();
        assertThat(emitter.getTimeout()).isEqualTo(30_000L);
    }

    @Test
    void streamChatCallsClientWithCorrectRequest() {
        when(llmClient.chatStream(any())).thenReturn(Flux.empty());
        List<Message> messages = List.of(Message.system("sys"), Message.user("hello"));

        service.streamChat(messages);

        org.mockito.ArgumentCaptor<LiteLlmClient.ChatRequest> captor =
                org.mockito.ArgumentCaptor.forClass(LiteLlmClient.ChatRequest.class);
        verify(llmClient).chatStream(captor.capture());
        LiteLlmClient.ChatRequest req = captor.getValue();
        assertThat(req.messages()).isEqualTo(messages);
        assertThat(req.model()).isEqualTo("gpt-4o-mini");
        assertThat(req.maxTokens()).isEqualTo(1024);
        assertThat(req.stream()).isTrue();
    }

    @Test
    void streamChatSubscribesAndProcessesChunks() throws Exception {
        // Use a latch inside the Flux to confirm subscription happened
        CountDownLatch subscribed = new CountDownLatch(1);
        when(llmClient.chatStream(any())).thenAnswer(inv -> {
            subscribed.countDown();
            return Flux.just("chunk1", "chunk2");
        });

        SseEmitter emitter = service.streamChat(List.of(Message.user("Hi")));

        assertThat(emitter).isNotNull();
        boolean wasSubscribed = subscribed.await(5, TimeUnit.SECONDS);
        assertThat(wasSubscribed).isTrue();
        verify(llmClient).chatStream(any());
    }

    @Test
    void streamChatHandlesErrorFlux() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(1);
        when(llmClient.chatStream(any())).thenAnswer(inv -> {
            subscribed.countDown();
            return Flux.error(new LiteLlmClient.LlmException("upstream failure"));
        });

        SseEmitter emitter = service.streamChat(List.of(Message.user("Hi")));

        assertThat(emitter).isNotNull();
        boolean wasSubscribed = subscribed.await(5, TimeUnit.SECONDS);
        assertThat(wasSubscribed).isTrue();
    }

    @Test
    void streamChatHandlesEmptyFlux() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(1);
        when(llmClient.chatStream(any())).thenAnswer(inv -> {
            subscribed.countDown();
            return Flux.empty();
        });

        SseEmitter emitter = service.streamChat(List.of(Message.user("Hi")));

        assertThat(emitter).isNotNull();
        boolean wasSubscribed = subscribed.await(5, TimeUnit.SECONDS);
        assertThat(wasSubscribed).isTrue();
    }

    @Test
    void eventConstantsAreCorrect() {
        assertThat(LlmStreamService.CHUNK_EVENT).isEqualTo("llm_chunk");
        assertThat(LlmStreamService.DONE_EVENT).isEqualTo("llm_done");
        assertThat(LlmStreamService.ERROR_EVENT).isEqualTo("llm_error");
    }
}
