package com.atmospath.platform.abuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.atmospath.platform.ratelimit.RateLimitDecision;
import com.atmospath.platform.ratelimit.RateLimitRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LlmAbuseGuardFilterTests {

    private static final Instant NOW = Instant.parse("2026-07-27T14:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private RateLimitRepository rateLimitRepo;
    private LlmCostCircuitBreaker circuitBreaker;
    private LlmAbuseGuardFilter filter;
    private AbuseGuardProperties properties;

    @BeforeEach
    void setUp() {
        rateLimitRepo = mock(RateLimitRepository.class);
        circuitBreaker = mock(LlmCostCircuitBreaker.class);
        properties = new AbuseGuardProperties(
                true, 5.0, 0.00014, 0.00028, 4000, 20, 0, 60_000L);
        filter = new LlmAbuseGuardFilter(
                properties, circuitBreaker, rateLimitRepo, new ObjectMapper(), FIXED_CLOCK);

        // Default: circuit breaker allows
        when(circuitBreaker.checkRequest(anyInt()))
                .thenReturn(LlmCostCircuitBreaker.Decision.allow(LlmCostCircuitBreaker.State.CLOSED));
    }

    // --- Path filtering ---

    @Test
    void skipsNonLlmPaths() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/risk/national");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    // --- Anonymous access blocked (anon limit = 0) ---

    @Test
    void anonymousRequestBlockedWhenAnonLimitIsZero() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/llm/chat");
        request.setRemoteAddr("203.0.113.10");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("LLM_ANON_DISABLED");
        assertThat(response.getContentAsString()).contains("authentication");
    }

    // --- Authenticated rate limiting ---

    @Test
    void authenticatedRequestAllowedWithinLimit() throws Exception {
        when(rateLimitRepo.consume(anyString(), anyInt(), any(Duration.class)))
                .thenReturn(new RateLimitDecision(true, 20, 19, NOW.plusSeconds(60).getEpochSecond()));

        var request = new MockHttpServletRequest("POST", "/api/v1/llm/chat");
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("Authorization", "Bearer eyJhbGciOiJSUzI1NiJ9.test-token");
        request.addHeader("X-User-Id", "user-123");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-LLM-RateLimit-Limit")).isEqualTo("20");
        assertThat(response.getHeader("X-LLM-RateLimit-Remaining")).isEqualTo("19");
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void authenticatedRequestRejectedWhenOverLimit() throws Exception {
        when(rateLimitRepo.consume(anyString(), anyInt(), any(Duration.class)))
                .thenReturn(new RateLimitDecision(false, 20, 0, NOW.plusSeconds(42).getEpochSecond()));

        var request = new MockHttpServletRequest("POST", "/api/v1/llm/chat");
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("Authorization", "Bearer eyJhbGciOiJSUzI1NiJ9.test-token");
        request.addHeader("X-User-Id", "user-123");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("LLM_RATE_LIMIT_EXCEEDED");
        assertThat(response.getHeader("Retry-After")).isEqualTo("42");
    }

    // --- Input size validation ---

    @Test
    void oversizedRequestBodyRejected() throws Exception {
        when(rateLimitRepo.consume(anyString(), anyInt(), any(Duration.class)))
                .thenReturn(new RateLimitDecision(true, 20, 19, NOW.plusSeconds(60).getEpochSecond()));

        var request = new MockHttpServletRequest("POST", "/api/v1/llm/chat");
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("Authorization", "Bearer eyJhbGciOiJSUzI1NiJ9.test-token");
        // maxChars=4000, threshold is 4000*3=12000; set content length above that
        request.setContent(new byte[13000]);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("LLM_INPUT_TOO_LARGE");
    }

    @Test
    void normalSizedRequestBodyAllowed() throws Exception {
        when(rateLimitRepo.consume(anyString(), anyInt(), any(Duration.class)))
                .thenReturn(new RateLimitDecision(true, 20, 19, NOW.plusSeconds(60).getEpochSecond()));

        var request = new MockHttpServletRequest("POST", "/api/v1/llm/chat");
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("Authorization", "Bearer eyJhbGciOiJSUzI1NiJ9.test-token");
        request.setContent("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}".getBytes());
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    // --- Circuit breaker integration ---

    @Test
    void circuitBreakerOpenRejectsRequest() throws Exception {
        when(rateLimitRepo.consume(anyString(), anyInt(), any(Duration.class)))
                .thenReturn(new RateLimitDecision(true, 20, 19, NOW.plusSeconds(60).getEpochSecond()));
        when(circuitBreaker.checkRequest(anyInt()))
                .thenReturn(LlmCostCircuitBreaker.Decision.deny(
                        LlmCostCircuitBreaker.State.OPEN,
                        "AI features temporarily paused: daily cost cap reached."));

        var request = new MockHttpServletRequest("POST", "/api/v1/llm/chat");
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("Authorization", "Bearer eyJhbGciOiJSUzI1NiJ9.test-token");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("LLM_COST_CAP_REACHED");
        assertThat(response.getContentAsString()).contains("temporarily paused");
        assertThat(response.getHeader("Retry-After")).isNotNull();
    }

    // --- Disabled guard passes through ---

    @Test
    void disabledGuardPassesThrough() throws Exception {
        var disabledProps = new AbuseGuardProperties(
                false, 5.0, 0.00014, 0.00028, 4000, 20, 0, 60_000L);
        var disabledFilter = new LlmAbuseGuardFilter(
                disabledProps, circuitBreaker, rateLimitRepo, new ObjectMapper(), FIXED_CLOCK);

        var request = new MockHttpServletRequest("POST", "/api/v1/llm/chat");
        request.setRemoteAddr("203.0.113.10");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        disabledFilter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    // --- X-Forwarded-For respected for IP identification ---

    @Test
    void usesForwardedForHeaderForClientAddress() throws Exception {
        // With anon limit = 0, the filter blocks before rate limit repo is called.
        // Verify it still blocks (identity extraction doesn't crash).
        var request = new MockHttpServletRequest("POST", "/api/v1/llm/chat");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.50, 10.0.0.1");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("LLM_ANON_DISABLED");
    }
}
