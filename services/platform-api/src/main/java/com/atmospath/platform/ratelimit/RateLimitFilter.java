package com.atmospath.platform.ratelimit;

import java.io.IOException;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
public class RateLimitFilter extends OncePerRequestFilter {
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final RateLimitRepository repository;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    @Autowired
    public RateLimitFilter(
            RateLimitRepository repository,
            RateLimitProperties properties,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this(repository, properties, objectMapper, Clock.systemUTC(), meterRegistry);
    }

    RateLimitFilter(RateLimitRepository repository, RateLimitProperties properties) {
        this(repository, properties, new ObjectMapper(), Clock.systemUTC(), io.micrometer.core.instrument.Metrics.globalRegistry);
    }

    RateLimitFilter(
            RateLimitRepository repository,
            RateLimitProperties properties,
            ObjectMapper objectMapper,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var bucket = classify(request);
        if (!properties.enabled() || bucket.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        var selectedBucket = bucket.get();
        var decision = repository.consume(keyFor(request, selectedBucket), selectedBucket.limit(), properties.window());
        addRateLimitHeaders(response, decision);
        recordMetric(selectedBucket, decision);
        if (decision.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }

        writeTooManyRequests(request, response, decision, selectedBucket);
    }

    private Optional<Bucket> classify(HttpServletRequest request) {
        var method = request.getMethod().toUpperCase(Locale.ROOT);
        var path = normalizePath(request.getRequestURI());
        if (method.equals("GET") && (
                path.equals("/risk/national")
                        || path.equals("/risk/weather-snapshot")
                        || path.equals("/risk/weather-raster")
                        || path.equals("/risk/weather-raster.png"))) {
            return Optional.of(new Bucket("public-risk-read", properties.publicReadLimitPerMinute()));
        }
        if (method.equals("GET") && path.equals("/places/search")) {
            return Optional.of(new Bucket("place-search", properties.mutationLimitPerMinute()));
        }
        if (method.equals("POST") && (path.equals("/directions") || path.equals("/risk/location"))) {
            return Optional.of(new Bucket("route-risk-mutation", properties.mutationLimitPerMinute()));
        }
        if (path.startsWith("/me/") || path.equals("/me")) {
            return Optional.of(new Bucket("authenticated-me", properties.authenticatedMutationLimitPerMinute()));
        }
        return Optional.empty();
    }

    private static String normalizePath(String requestUri) {
        if (requestUri == null || requestUri.isBlank()) return "/";
        if (requestUri.startsWith("/api/v1/")) return requestUri.substring("/api/v1".length());
        if (requestUri.equals("/api/v1")) return "/";
        return requestUri;
    }

    private static String keyFor(HttpServletRequest request, Bucket bucket) {
        return bucket.name() + ":" + request.getMethod().toUpperCase(Locale.ROOT) + ":" + normalizePath(request.getRequestURI())
                + ":" + clientAddress(request);
    }

    private static String clientAddress(HttpServletRequest request) {
        var remoteAddr = request.getRemoteAddr();
        if (remoteAddr == null || remoteAddr.isBlank()) return "unknown";
        return remoteAddr;
    }

    private void addRateLimitHeaders(HttpServletResponse response, RateLimitDecision decision) {
        response.setHeader("X-RateLimit-Limit", Integer.toString(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", Integer.toString(decision.remaining()));
        response.setHeader("X-RateLimit-Reset", Long.toString(decision.resetEpochSecond()));
    }

    private void recordMetric(Bucket bucket, RateLimitDecision decision) {
        meterRegistry.counter("atmospath.rate_limit.requests",
                "bucket", bucket.name(),
                "outcome", decision.allowed() ? "allowed" : "denied").increment();
    }

    private void writeTooManyRequests(
            HttpServletRequest request,
            HttpServletResponse response,
            RateLimitDecision decision,
            Bucket bucket) throws IOException {
        var retryAfter = decision.retryAfterSeconds(clock.instant().getEpochSecond());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), new RateLimitError(new RateLimitErrorBody(
                "RATE_LIMIT_EXCEEDED",
                "Too many requests. Retry after " + retryAfter + " seconds.",
                safeRequestId(request),
                new RateLimitDetails(bucket.name(), decision.limit(), decision.remaining(), retryAfter, decision.resetEpochSecond()))));
    }

    private static String safeRequestId(HttpServletRequest request) {
        var requestId = request.getHeader(REQUEST_ID_HEADER);
        return requestId == null || requestId.isBlank() ? "unknown" : requestId;
    }

    private record Bucket(String name, int limit) {
    }

    private record RateLimitError(RateLimitErrorBody error) {
    }

    private record RateLimitErrorBody(String code, String message, String requestId, RateLimitDetails details) {
    }

    private record RateLimitDetails(
            String bucket,
            int limit,
            int remaining,
            long retryAfterSeconds,
            long resetEpochSecond) {
    }
}
