package com.atmospath.platform.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class InMemoryRateLimitRepositoryTests {
    @Test
    void deniesRequestsAfterTheFixedWindowLimit() {
        var clock = new MutableClock(Instant.parse("2026-07-07T12:00:00Z"));
        var repository = new InMemoryRateLimitRepository(clock);

        var first = repository.consume("route-plan:127.0.0.1", 2, Duration.ofMinutes(1));
        var second = repository.consume("route-plan:127.0.0.1", 2, Duration.ofMinutes(1));
        var third = repository.consume("route-plan:127.0.0.1", 2, Duration.ofMinutes(1));

        assertThat(first.allowed()).isTrue();
        assertThat(first.remaining()).isEqualTo(1);
        assertThat(second.allowed()).isTrue();
        assertThat(second.remaining()).isZero();
        assertThat(third.allowed()).isFalse();
        assertThat(third.remaining()).isZero();
    }

    @Test
    void resetsCountsAfterTheWindowRollsForward() {
        var clock = new MutableClock(Instant.parse("2026-07-07T12:00:00Z"));
        var repository = new InMemoryRateLimitRepository(clock);

        repository.consume("route-plan:127.0.0.1", 1, Duration.ofMinutes(1));
        assertThat(repository.consume("route-plan:127.0.0.1", 1, Duration.ofMinutes(1)).allowed()).isFalse();

        clock.now = Instant.parse("2026-07-07T12:01:00Z");

        var allowedAgain = repository.consume("route-plan:127.0.0.1", 1, Duration.ofMinutes(1));
        assertThat(allowedAgain.allowed()).isTrue();
        assertThat(allowedAgain.remaining()).isZero();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
