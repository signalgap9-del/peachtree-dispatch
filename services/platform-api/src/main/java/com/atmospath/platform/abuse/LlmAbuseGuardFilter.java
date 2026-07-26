package com.atmospath.platform.abuse;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

import com.atmospath.platform.ratelimit.RateLimitDecision;
import com.atmospath.platform.ratelimit.RateLimitRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Application-level financial guard for LLM endpoints.
 * Ordered between the general RateLimitFilter (+40) and the existing
 * LlmSecurityFilter (+50).
 *
 * Enforces in order: (1) LLM-specific per-user/per-IP rate limits,
 * (2) input size validation to reject token-bombing,
 * (3) daily cost circuit breaker (the money guard).
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 45)
public class LlmAbuseGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LlmAbuseGuardFilter.class);
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);
    private static final String LLM_PATH_PREFIX = "/api/v1/llm";

    private final AbuseGuardProperties properties;
    private final LlmCostCircuitBreaker circuitBreaker;
    private final RateLimitRepository rateLimitRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public LlmAbuseGuardFilter(
            AbuseGuardProperties properties,
            LlmCostCircuitBreaker circuitBreaker,
            RateLimitRepository rateLimitRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.properties = properties;
        this.circuitBreaker = circuitBreaker;
        this.rateLimitRepository = rateLimitRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(LLM_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if (!properties.enabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        // 1. LLM-specific rate limiting (per-IP, with auth-aware limits)
        if (!checkRateLimit(request, response)) {
            return;
        }

        // 2. Input size validation (only for POST with body)
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            if (!checkInputSize(request, response)) {
                return;
            }
        }

        // 3. Cost circuit breaker
        int estimatedChars = estimateInputChars(request);
        LlmCostCircuitBreaker.Decision decision = circuitBreaker.checkRequest(estimatedChars);
        if (!decision.allowed()) {
            log.warn("LLM cost circuit breaker OPEN: rejecting request from {}", clientAddress(request));
            writeError(response, HttpStatus.TOO_MANY_REQUESTS,
                    "LLM_COST_CAP_REACHED", decision.reason(), retryAfterForCircuit());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean checkRateLimit(HttpServletRequest request, HttpServletResponse response) throws IOException {
        boolean authenticated = isAuthenticated(request);
        int limit = authenticated ? properties.llmRatePerMinuteAuth() : properties.llmRatePerMinuteAnon();

        if (limit <= 0) {
            writeError(response, HttpStatus.TOO_MANY_REQUESTS,
                    "LLM_ANON_DISABLED",
                    "AI features require authentication. Please sign in to use AI assistance.",
                    60L);
            return false;
        }

        String identity = authenticated ? userIdentity(request) : clientAddress(request);
        String key = "llm-abuse:" + identity;
        RateLimitDecision decision = rateLimitRepository.consume(key, limit, RATE_WINDOW);

        response.setHeader("X-LLM-RateLimit-Limit", Integer.toString(decision.limit()));
        response.setHeader("X-LLM-RateLimit-Remaining", Integer.toString(decision.remaining()));

        if (!decision.allowed()) {
            long retryAfter = decision.retryAfterSeconds(clock.instant().getEpochSecond());
            response.setHeader("Retry-After", Long.toString(retryAfter));
            writeError(response, HttpStatus.TOO_MANY_REQUESTS,
                    "LLM_RATE_LIMIT_EXCEEDED",
                    "Too many AI requests. Retry after " + retryAfter + " seconds.",
                    retryAfter);
            return false;
        }
        return true;
    }

    private boolean checkInputSize(HttpServletRequest request, HttpServletResponse response) throws IOException {
        int contentLength = request.getContentLength();
        int maxChars = properties.llmMaxInputChars();

        // Content-Length gives a fast pre-check; JSON overhead means body chars < bytes
        if (contentLength > maxChars * 3) {
            writeError(response, HttpStatus.BAD_REQUEST,
                    "LLM_INPUT_TOO_LARGE",
                    "Request body too large. Maximum input is " + maxChars + " characters.",
                    0L);
            return false;
        }
        return true;
    }

    private static int estimateInputChars(HttpServletRequest request) {
        int contentLength = request.getContentLength();
        return contentLength > 0 ? contentLength : 0;
    }

    private static boolean isAuthenticated(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ") && auth.length() > 7) {
            return true;
        }
        String userId = request.getHeader("X-User-Id");
        return userId != null && !userId.isBlank();
    }

    private static String userIdentity(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId != null && !userId.isBlank()) {
            return "user:" + userId;
        }
        // Fall back to a hash of the bearer token prefix (don't store full token)
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.length() > 20) {
            return "token:" + Integer.toHexString(auth.substring(7, 20).hashCode());
        }
        return "ip:" + clientAddress(request);
    }

    private static String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr == null || remoteAddr.isBlank() ? "unknown" : remoteAddr;
    }

    private long retryAfterForCircuit() {
        var now = clock.instant();
        var midnight = now.atZone(ZoneOffset.UTC).toLocalDate()
                .plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        long seconds = Duration.between(now, midnight).getSeconds();
        return Math.min(seconds, 3600L);
    }

    private void writeError(
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String message,
            long retryAfterSeconds) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (retryAfterSeconds > 0) {
            response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        }
        objectMapper.writeValue(response.getWriter(),
                new AbuseError(new AbuseErrorBody(code, message)));
    }

    private record AbuseError(AbuseErrorBody error) {}

    private record AbuseErrorBody(String code, String message) {}
}
