package com.atmospath.platform.ratelimit;

import java.time.Duration;

public interface RateLimitRepository {
    RateLimitDecision consume(String key, int limit, Duration window);
}
