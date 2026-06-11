package com.atmospath.platform.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class OriginVerificationFilter extends OncePerRequestFilter {
    private final String expectedSecret;

    OriginVerificationFilter(@Value("${atmospath.api-origin-verify-secret:}") String expectedSecret) {
        this.expectedSecret = expectedSecret;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!expectedSecret.isBlank() && !matches(request.getHeader("X-Origin-Verify"))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean matches(String suppliedSecret) {
        return suppliedSecret != null && MessageDigest.isEqual(
                expectedSecret.getBytes(StandardCharsets.UTF_8),
                suppliedSecret.getBytes(StandardCharsets.UTF_8));
    }
}
