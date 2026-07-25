package com.atmospath.platform.llm.nl2opt;

import java.util.List;

import com.atmospath.platform.llm.Message;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.VrpConstraints;

/**
 * Carries multi-turn conversation state into the extraction pipeline. When
 * {@code previousConstraints} is non-null the extractor includes them as
 * "current state" so the LLM can produce incremental modifications rather
 * than a full re-extraction.
 */
public record ConversationContext(VrpConstraints previousConstraints, List<Message> history) {

    /** Empty context for a fresh conversation. */
    public static ConversationContext empty() {
        return new ConversationContext(null, List.of());
    }

    public boolean hasPreviousConstraints() {
        return previousConstraints != null;
    }
}
