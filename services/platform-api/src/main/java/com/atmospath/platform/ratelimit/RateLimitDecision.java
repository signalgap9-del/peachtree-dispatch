package com.atmospath.platform.ratelimit;

public record RateLimitDecision(
        boolean allowed,
        int limit,
        int remaining,
        long resetEpochSecond) {
    public long retryAfterSeconds(long nowEpochSecond) {
        return Math.max(1, resetEpochSecond - nowEpochSecond);
    }
}
