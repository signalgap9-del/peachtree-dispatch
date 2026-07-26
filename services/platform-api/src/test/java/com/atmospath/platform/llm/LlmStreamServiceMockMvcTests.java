package com.atmospath.platform.llm;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

/**
 * MockMvc-based tests that exercise the full SSE send path in
 * {@link LlmStreamService}, covering the happy-path emitter.send()
 * calls and the {@code escapeJson} helper.
 */
class LlmStreamServiceMockMvcTests {

    private LiteLlmClient llmClient;
    private LlmStreamService streamService;
    private MockMvc mockMvc;

    @RestController
    static class TestSseController {
        private final LlmStreamService streamService;

        TestSseController(LlmStreamService streamService) {
            this.streamService = streamService;
        }

        @PostMapping("/test/stream")
        public SseEmitter stream() {
            return streamService.streamChat(List.of(Message.user("hello")));
        }
    }

    @BeforeEach
    void setUp() {
        llmClient = mock(LiteLlmClient.class);
        LlmProperties props = new LlmProperties(true, "http://localhost:4000", "key",
                "gpt-4o-mini", 1024, 0.7, 30_000L, 100_000L, 4096);
        streamService = new LlmStreamService(llmClient, props);
        mockMvc = MockMvcBuilders.standaloneSetup(new TestSseController(streamService)).build();
    }

    @Test
    void streamSendsChunkAndDoneEvents() throws Exception {
        when(llmClient.chatStream(any()))
                .thenReturn(Flux.just("Hello", " world"));

        MvcResult mvcResult = mockMvc.perform(post("/test/stream")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(request().asyncStarted())
                .andReturn();

        // Wait for async to complete
        Thread.sleep(500);

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk());

        String content = mvcResult.getResponse().getContentAsString();
        // Verify SSE event names and JSON-escaped content appear
        org.assertj.core.api.Assertions.assertThat(content).contains("llm_chunk");
        org.assertj.core.api.Assertions.assertThat(content).contains("llm_done");
        org.assertj.core.api.Assertions.assertThat(content).contains("Hello");
    }

    @Test
    void streamEscapesSpecialCharactersInChunks() throws Exception {
        when(llmClient.chatStream(any()))
                .thenReturn(Flux.just("line1\nline2", "tab\there", "quote\"end"));

        MvcResult mvcResult = mockMvc.perform(post("/test/stream")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(request().asyncStarted())
                .andReturn();

        Thread.sleep(500);

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk());

        String content = mvcResult.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(content).contains("\\n");
        org.assertj.core.api.Assertions.assertThat(content).contains("\\t");
        org.assertj.core.api.Assertions.assertThat(content).contains("\\\"");
    }

    @Test
    void streamSendsErrorEvent() throws Exception {
        when(llmClient.chatStream(any()))
                .thenReturn(Flux.error(new LiteLlmClient.LlmException("test error")));

        MvcResult mvcResult = mockMvc.perform(post("/test/stream")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(request().asyncStarted())
                .andReturn();

        Thread.sleep(500);

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk());

        String content = mvcResult.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(content).contains("llm_error");
        org.assertj.core.api.Assertions.assertThat(content).contains("test error");
    }
}
