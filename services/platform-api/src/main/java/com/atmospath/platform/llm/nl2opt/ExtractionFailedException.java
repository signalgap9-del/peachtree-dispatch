package com.atmospath.platform.llm.nl2opt;

import java.util.List;

/**
 * Thrown when the LLM repair loop exhausts all attempts without producing
 * valid VRP constraints. Carries the last validation errors so callers can
 * surface actionable feedback to the user.
 */
public class ExtractionFailedException extends RuntimeException {

    private final List<String> validationErrors;
    private final int attemptsMade;

    public ExtractionFailedException(List<String> validationErrors, int attemptsMade) {
        super("Constraint extraction failed after " + attemptsMade + " attempt(s): " + validationErrors);
        this.validationErrors = List.copyOf(validationErrors);
        this.attemptsMade = attemptsMade;
    }

    public List<String> validationErrors() {
        return validationErrors;
    }

    public int attemptsMade() {
        return attemptsMade;
    }
}
