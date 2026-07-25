package com.atmospath.platform.llm;

/**
 * A single chat message exchanged with an LLM. Concrete {@link LlmClient}
 * implementations map this to their provider-specific request format.
 */
public record Message(Role role, String content) {

    public enum Role {
        SYSTEM, USER, ASSISTANT
    }

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content);
    }

    public static Message user(String content) {
        return new Message(Role.USER, content);
    }
}
