package com.atmospath.platform.llm.security;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gateway filter for {@code /api/v1/llm/**} endpoints. Returns 503 when LLM is
 * disabled, 429 when the token budget is exhausted, and logs request/tenant IDs
 * for audit. Ordered after {@link com.atmospath.platform.ratelimit.RateLimitFilter}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class LlmSecurityFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(LlmSecurityFilter.class);
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String TENANT_HEADER = "X-Tenant-Id";

    private final boolean llmEnabled;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<LlmTokenBudget> tokenBudgetProvider;

    @Autowired
    public LlmSecurityFilter(
            @Value("${atmospath.llm.enabled:false}") boolean llmEnabled,
            ObjectMapper objectMapper,
            ObjectProvider<LlmTokenBudget> tokenBudgetProvider) {
        this.llmEnabled = llmEnabled;
        this.objectMapper = objectMapper;
        this.tokenBudgetProvider = tokenBudgetProvider;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/llm");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var requestId = safeHeader(request, REQUEST_ID_HEADER);
        var tenantId = safeHeader(request, TENANT_HEADER);
        log.info("LLM request audit: requestId={}, tenant={}", requestId, tenantId);

        if (!llmEnabled) {
            writeError(response, HttpStatus.SERVICE_UNAVAILABLE,
                    "LLM_UNAVAILABLE", "LLM features are currently disabled.", requestId);
            return;
        }

        var budget = tokenBudgetProvider.getIfAvailable();
        if (budget != null && !budget.tryConsume(tenantId)) {
            var retryAfter = budget.retryAfterSeconds();
            response.setHeader("Retry-After", Long.toString(retryAfter));
            writeError(response, HttpStatus.TOO_MANY_REQUESTS,
                    "LLM_TOKEN_BUDGET_EXCEEDED",
                    "LLM token budget exhausted. Retry after " + retryAfter + " seconds.",
                    requestId);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeError(
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String message,
            String requestId) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                new LlmError(new LlmErrorBody(code, message, requestId)));
    }

    private static String safeHeader(HttpServletRequest request, String name) {
        var value = request.getHeader(name);
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private record LlmError(LlmErrorBody error) {
    }

    private record LlmErrorBody(String code, String message, String requestId) {
    }
}
