package com.atmospath.platform.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "atmospath.rate-limit.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryRateLimitRepository implements RateLimitRepository {
    private static final int MAX_TRACKED_BUCKETS = 20_000;

    private final ConcurrentHashMap<String, CounterState> counters = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryRateLimitRepository() {
        this(Clock.systemUTC());
    }

    InMemoryRateLimitRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public RateLimitDecision consume(String key, int limit, Duration window) {
        var nowMs = clock.millis();
        var windowMs = window.toMillis();
        var windowStartMs = nowMs - (nowMs % windowMs);
        var resetEpochSecond = (windowStartMs + windowMs) / 1000;
        var decision = new RateLimitDecision[1];

        counters.compute(key, (ignored, current) -> {
            var state = current;
            if (state == null || state.windowStartMs != windowStartMs) {
                state = new CounterState(windowStartMs, 0);
            }
            state.count += 1;
            var allowed = state.count <= limit;
            var remaining = Math.max(0, limit - state.count);
            decision[0] = new RateLimitDecision(allowed, limit, remaining, resetEpochSecond);
            return state;
        });

        if (counters.size() > MAX_TRACKED_BUCKETS) {
            pruneExpired(windowStartMs);
        }

        return decision[0];
    }

    private void pruneExpired(long activeWindowStartMs) {
        counters.entrySet().removeIf(entry -> entry.getValue().windowStartMs < activeWindowStartMs);
    }

    private static final class CounterState {
        private final long windowStartMs;
        private int count;

        private CounterState(long windowStartMs, int count) {
            this.windowStartMs = windowStartMs;
            this.count = count;
        }
    }
}
