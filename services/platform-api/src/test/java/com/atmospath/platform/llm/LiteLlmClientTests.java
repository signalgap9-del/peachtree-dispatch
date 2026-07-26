package com.atmospath.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Tests for {@link LiteLlmClient} using Spring's {@link MockRestServiceServer}
 * to exercise the real RestClient exchange path without a live server.
 */
class LiteLlmClientTests {

    private static final String API_KEY = "test-api-key";

    private LiteLlmClient client;
    private MockRestServiceServer mockServer;
    private LlmProperties properties;

    @BeforeEach
    void setUp() {
        properties = new LlmProperties(true, "http://localhost:4000", API_KEY,
                "gpt-4o-mini", 1024, 0.7, 60_000L, 100_000L, 4096);
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        client = new LiteLlmClient(properties, restClient, new ObjectMapper());
    }

    @Test
    void chatParsesSuccessfulResponse() {
        String json = """
                {
                  "choices": [{"message": {"content": "Hello from LLM"}}],
                  "model": "gpt-4o-mini",
                  "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                }
                """;
        mockServer.expect(requestTo("/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        LiteLlmClient.ChatRequest request = new LiteLlmClient.ChatRequest(
                List.of(Message.user("Hi")), "gpt-4o-mini", 1024, 0.7, false);
        LiteLlmClient.ChatCompletion result = client.chat(request);

        assertThat(result.content()).isEqualTo("Hello from LLM");
        assertThat(result.model()).isEqualTo("gpt-4o-mini");
        assertThat(result.usage().promptTokens()).isEqualTo(10);
        assertThat(result.usage().completionTokens()).isEqualTo(5);
        assertThat(result.usage().totalTokens()).isEqualTo(15);
        mockServer.verify();
    }

    @Test
    void completeDelegatesToChatAndReturnsContent() {
        String json = """
                {
                  "choices": [{"message": {"content": "Delegated response"}}],
                  "model": "gpt-4o-mini",
                  "usage": {"prompt_tokens": 1, "completion_tokens": 2, "total_tokens": 3}
                }
                """;
        mockServer.expect(requestTo("/v1/chat/completions"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        String result = client.complete(List.of(Message.user("test")));

        assertThat(result).isEqualTo("Delegated response");
        mockServer.verify();
    }

    @Test
    void chatThrowsRateLimitExceptionOn429() {
        mockServer.expect(requestTo("/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        LiteLlmClient.ChatRequest request = new LiteLlmClient.ChatRequest(
                List.of(Message.user("Hi")), "gpt-4o-mini", 1024, 0.7, false);

        assertThatThrownBy(() -> client.chat(request))
                .isInstanceOf(LiteLlmClient.LlmRateLimitException.class)
                .hasMessageContaining("rate limit");
    }

    @Test
    void chatThrowsLlmExceptionOn500() {
        mockServer.expect(requestTo("/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        LiteLlmClient.ChatRequest request = new LiteLlmClient.ChatRequest(
                List.of(Message.user("Hi")), "gpt-4o-mini", 1024, 0.7, false);

        assertThatThrownBy(() -> client.chat(request))
                .isInstanceOf(LiteLlmClient.LlmException.class)
                .hasMessageContaining("server error");
    }

    @Test
    void chatThrowsLlmExceptionOn4xx() {
        mockServer.expect(requestTo("/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        LiteLlmClient.ChatRequest request = new LiteLlmClient.ChatRequest(
                List.of(Message.user("Hi")), "gpt-4o-mini", 1024, 0.7, false);

        assertThatThrownBy(() -> client.chat(request))
                .isInstanceOf(LiteLlmClient.LlmException.class)
                .hasMessageContaining("HTTP 400");
    }

    @Test
    void chatStreamParsesSseChunks() {
        String sseBody = """
                data: {"choices":[{"delta":{"content":"Hello"}}]}

                data: {"choices":[{"delta":{"content":" world"}}]}

                data: [DONE]

                """;
        mockServer.expect(requestTo("/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andRespond(withSuccess(sseBody, MediaType.TEXT_PLAIN));

        LiteLlmClient.ChatRequest request = new LiteLlmClient.ChatRequest(
                List.of(Message.user("Hi")), "gpt-4o-mini", 1024, 0.7, true);
        Flux<String> stream = client.chatStream(request);

        StepVerifier.create(stream)
                .expectNext("Hello")
                .expectNext(" world")
                .verifyComplete();
        mockServer.verify();
    }

    @Test
    void chatStreamSkipsNonDataLinesAndEmptyDeltas() {
        String sseBody = """
                : comment line
                data: {"choices":[{"delta":{"content":"A"}}]}

                data: {"choices":[{"delta":{}}]}

                data: {"choices":[{"delta":{"content":"B"}}]}

                data: [DONE]

                """;
        mockServer.expect(requestTo("/v1/chat/completions"))
                .andRespond(withSuccess(sseBody, MediaType.TEXT_PLAIN));

        LiteLlmClient.ChatRequest request = new LiteLlmClient.ChatRequest(
                List.of(Message.user("Hi")), "gpt-4o-mini", 1024, 0.7, true);

        StepVerifier.create(client.chatStream(request))
                .expectNext("A")
                .expectNext("B")
                .verifyComplete();
    }

    @Test
    void chatStreamEmitsErrorOn429() {
        mockServer.expect(requestTo("/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        LiteLlmClient.ChatRequest request = new LiteLlmClient.ChatRequest(
                List.of(Message.user("Hi")), "gpt-4o-mini", 1024, 0.7, true);

        StepVerifier.create(client.chatStream(request))
                .expectError(LiteLlmClient.LlmRateLimitException.class)
                .verify();
    }

    @Test
    void chatStreamHandlesMalformedChunkGracefully() {
        String sseBody = """
                data: {not valid json}

                data: {"choices":[{"delta":{"content":"OK"}}]}

                data: [DONE]

                """;
        mockServer.expect(requestTo("/v1/chat/completions"))
                .andRespond(withSuccess(sseBody, MediaType.TEXT_PLAIN));

        LiteLlmClient.ChatRequest request = new LiteLlmClient.ChatRequest(
                List.of(Message.user("Hi")), "gpt-4o-mini", 1024, 0.7, true);

        StepVerifier.create(client.chatStream(request))
                .expectNext("OK")
                .verifyComplete();
    }

    @Test
    void chatRequestWithStreamReturnsNewInstance() {
        LiteLlmClient.ChatRequest original = new LiteLlmClient.ChatRequest(
                List.of(Message.user("Hi")), "gpt-4o-mini", 1024, 0.7, false);
        LiteLlmClient.ChatRequest streamed = original.withStream(true);

        assertThat(original.stream()).isFalse();
        assertThat(streamed.stream()).isTrue();
        assertThat(streamed.messages()).isEqualTo(original.messages());
    }
}
