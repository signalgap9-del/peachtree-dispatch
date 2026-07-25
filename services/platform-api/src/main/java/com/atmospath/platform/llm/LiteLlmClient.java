package com.atmospath.platform.llm;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;

/**
 * OpenAI-compatible HTTP client that talks to LiteLLM Proxy.
 * Implements the platform {@link LlmClient} contract and adds
 * streaming support via {@link #chatStream(ChatRequest)}.
 */
public class LiteLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LiteLlmClient.class);
    private static final String DONE_MARKER = "[DONE]";
    private static final String SSE_DATA_PREFIX = "data: ";

    private final LlmProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public LiteLlmClient(LlmProperties properties, RestClient restClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    // ---- DTOs ----

    public record ChatRequest(
            List<Message> messages,
            String model,
            int maxTokens,
            double temperature,
            boolean stream) {

        public ChatRequest withStream(boolean stream) {
            return new ChatRequest(messages, model, maxTokens, temperature, stream);
        }
    }

    public record Usage(int promptTokens, int completionTokens, int totalTokens) {}

    public record ChatCompletion(String content, String model, Usage usage) {}

    // ---- Exceptions ----

    public static class LlmException extends RuntimeException {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class LlmRateLimitException extends LlmException {
        public LlmRateLimitException(String message) {
            super(message);
        }
    }

    // ---- LlmClient contract ----

    @Override
    public String complete(List<Message> messages) {
        ChatRequest request = new ChatRequest(
                messages, properties.model(), properties.maxTokens(),
                properties.temperature(), false);
        return chat(request).content();
    }

    // ---- Extended API ----

    /**
     * Non-streaming chat completion. Blocks until the full response is available.
     */
    public ChatCompletion chat(ChatRequest request) {
        ChatRequest body = request.withStream(false);
        return restClient.post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + properties.apiKey())
                .body(toRequestBody(body))
                .exchange((req, resp) -> {
                    checkStatus(resp.getStatusCode());
                    JsonNode root = objectMapper.readTree(resp.getBody());
                    String content = root.path("choices").path(0)
                            .path("message").path("content").asText("");
                    String model = root.path("model").asText("");
                    JsonNode u = root.path("usage");
                    Usage usage = new Usage(
                            u.path("prompt_tokens").asInt(0),
                            u.path("completion_tokens").asInt(0),
                            u.path("total_tokens").asInt(0));
                    return new ChatCompletion(content, model, usage);
                });
    }

    /**
     * Streaming chat completion. Returns a {@link Flux} of content deltas
     * parsed from the SSE response. The blocking read happens inside
     * {@code Flux.create}; callers should subscribe on a bounded-elastic
     * scheduler.
     */
    public Flux<String> chatStream(ChatRequest request) {
        ChatRequest body = request.withStream(true);
        return Flux.create(sink -> {
            try {
                restClient.post()
                        .uri("/v1/chat/completions")
                        .header("Authorization", "Bearer " + properties.apiKey())
                        .body(toRequestBody(body))
                        .exchange((req, resp) -> {
                            checkStatus(resp.getStatusCode());
                            try (BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(resp.getBody(), StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    if (sink.isCancelled()) {
                                        break;
                                    }
                                    if (!line.startsWith(SSE_DATA_PREFIX)) {
                                        continue;
                                    }
                                    String data = line.substring(SSE_DATA_PREFIX.length()).trim();
                                    if (DONE_MARKER.equals(data)) {
                                        break;
                                    }
                                    String content = extractDelta(data);
                                    if (content != null && !content.isEmpty()) {
                                        sink.next(content);
                                    }
                                }
                            }
                            return null;
                        });
                sink.complete();
            } catch (Exception ex) {
                sink.error(ex);
            }
        });
    }

    // ---- Internals ----

    /** Builds a plain map so Jackson serializes roles as lowercase strings. */
    private Map<String, Object> toRequestBody(ChatRequest request) {
        List<Map<String, String>> apiMessages = request.messages().stream()
                .map(m -> Map.of(
                        "role", m.role().name().toLowerCase(Locale.ROOT),
                        "content", m.content()))
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", apiMessages);
        body.put("model", request.model());
        body.put("max_tokens", request.maxTokens());
        body.put("temperature", request.temperature());
        body.put("stream", request.stream());
        return body;
    }

    private void checkStatus(HttpStatusCode status) {
        if (status.value() == 429) {
            throw new LlmRateLimitException("LLM provider rate limit exceeded");
        }
        if (status.is5xxServerError()) {
            throw new LlmException("LLM provider server error (HTTP " + status.value() + ")");
        }
        if (status.isError()) {
            throw new LlmException("LLM request failed with HTTP " + status.value());
        }
    }

    private String extractDelta(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            return root.path("choices").path(0)
                    .path("delta").path("content").asText(null);
        } catch (Exception ex) {
            log.debug("Skipping unparseable SSE chunk: {}", json);
            return null;
        }
    }
}
