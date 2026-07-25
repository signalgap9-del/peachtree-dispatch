package com.atmospath.platform.llm;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.scheduler.Schedulers;

/**
 * Bridges {@link LiteLlmClient} streaming responses to Spring MVC
 * {@link SseEmitter}s, following the same emitter lifecycle pattern
 * used by {@code AlertStreamService}.
 */
public class LlmStreamService {

    static final String CHUNK_EVENT = "llm_chunk";
    static final String DONE_EVENT = "llm_done";
    static final String ERROR_EVENT = "llm_error";

    private static final Logger log = LoggerFactory.getLogger(LlmStreamService.class);

    private final LiteLlmClient llmClient;
    private final LlmProperties properties;

    public LlmStreamService(LiteLlmClient llmClient, LlmProperties properties) {
        this.llmClient = llmClient;
        this.properties = properties;
    }

    /**
     * Opens an SSE stream that relays LLM content deltas to the client.
     * <ul>
     *   <li>{@code llm_chunk} - one per content delta</li>
     *   <li>{@code llm_done}  - final event with usage summary</li>
     *   <li>{@code llm_error} - on any upstream or I/O failure</li>
     * </ul>
     */
    public SseEmitter streamChat(List<Message> messages) {
        SseEmitter emitter = new SseEmitter(properties.timeoutMs());
        emitter.onCompletion(() -> log.debug("LLM SSE emitter completed"));
        emitter.onTimeout(() -> {
            log.debug("LLM SSE emitter timed out after {} ms", properties.timeoutMs());
            emitter.complete();
        });
        emitter.onError(ex -> log.debug("LLM SSE emitter error: {}", ex.toString()));

        LiteLlmClient.ChatRequest request = new LiteLlmClient.ChatRequest(
                messages,
                properties.model(),
                properties.maxTokens(),
                properties.temperature(),
                true);

        llmClient.chatStream(request)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        chunk -> sendChunk(emitter, chunk),
                        error -> sendError(emitter, error),
                        () -> sendDone(emitter));

        return emitter;
    }

    private void sendChunk(SseEmitter emitter, String content) {
        try {
            String json = "{\"content\":" + escapeJson(content) + "}";
            emitter.send(SseEmitter.event()
                    .name(CHUNK_EVENT)
                    .data(json, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ex) {
            log.debug("Failed to send LLM chunk; completing emitter: {}", ex.toString());
            completeQuietly(emitter);
        }
    }

    private void sendDone(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event()
                    .name(DONE_EVENT)
                    .data("{\"usage\":{}}", MediaType.APPLICATION_JSON));
            emitter.complete();
            log.info("LLM stream finished");
        } catch (IOException | IllegalStateException ex) {
            log.debug("Failed to send LLM done event: {}", ex.toString());
            completeQuietly(emitter);
        }
    }

    private void sendError(SseEmitter emitter, Throwable error) {
        log.warn("LLM stream error: {}", error.toString());
        try {
            String message = error.getMessage() != null ? error.getMessage() : "Unknown LLM error";
            String json = "{\"error\":" + escapeJson(message) + "}";
            emitter.send(SseEmitter.event()
                    .name(ERROR_EVENT)
                    .data(json, MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException | IllegalStateException ex) {
            log.debug("Failed to send LLM error event: {}", ex.toString());
            completeQuietly(emitter);
        }
    }

    private static void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // Connection already gone.
        }
    }

    /** Minimal JSON string escaping for embedding in hand-built JSON payloads. */
    private static String escapeJson(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
