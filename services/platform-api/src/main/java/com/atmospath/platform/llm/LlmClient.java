package com.atmospath.platform.llm;

import java.util.List;

/**
 * Minimal chat-completion abstraction used by the platform's LLM features
 * (intent classification, NL2Opt extraction, explanations). A concrete
 * Bedrock/HTTP implementation is supplied in Phase 2; services program
 * against this interface so they can be unit tested with a stub.
 */
public interface LlmClient {

    /**
     * Sends the messages as a chat completion and returns the assistant's
     * raw text response.
     *
     * @throws RuntimeException when the provider call fails or times out
     */
    String complete(List<Message> messages);
}
