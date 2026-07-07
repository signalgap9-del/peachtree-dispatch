package com.atmospath.platform.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTests {
    private static final Instant NOW = Instant.parse("2026-07-07T12:00:00Z");

    @Test
    void writesStructuredTooManyRequestsResponseWhenBucketIsExhausted() throws Exception {
        var repository = new DenyingRateLimitRepository();
        var properties = new RateLimitProperties(true, Duration.ofMinutes(1), 100, 30, 10);
        var meterRegistry = new SimpleMeterRegistry();
        var filter = new RateLimitFilter(repository, properties, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), meterRegistry);
        var request = new MockHttpServletRequest("POST", "/api/v1/directions");
        var response = new MockHttpServletResponse();
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("X-Request-Id", "req-rate-limit-test");

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("42");
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("30");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(response.getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
        assertThat(response.getContentAsString()).contains("req-rate-limit-test");
        assertThat(meterRegistry.get("atmospath.rate_limit.requests")
                .tag("bucket", "route-risk-mutation")
                .tag("outcome", "denied")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    void skipsUnknownPathsToAvoidBreakingHealthAndStaticAssets() throws Exception {
        var repository = new DenyingRateLimitRepository();
        var properties = new RateLimitProperties(true, Duration.ofMinutes(1), 100, 30, 10);
        var filter = new RateLimitFilter(repository, properties);
        var request = new MockHttpServletRequest("GET", "/health");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static final class DenyingRateLimitRepository implements RateLimitRepository {
        @Override
        public RateLimitDecision consume(String key, int limit, Duration window) {
            return new RateLimitDecision(false, limit, 0, NOW.plusSeconds(42).getEpochSecond());
        }
    }
}
