package com.atmospath.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTests {
    @Test
    void propagatesSafeInboundRequestId() throws Exception {
        var filter = new RequestIdFilter();
        var request = new MockHttpServletRequest("GET", "/health");
        var response = new MockHttpServletResponse();
        request.addHeader("X-Request-Id", "req-123.safe");

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("X-Request-Id")).isEqualTo("req-123.safe");
    }

    @Test
    void replacesUnsafeInboundRequestId() throws Exception {
        var filter = new RequestIdFilter();
        var request = new MockHttpServletRequest("GET", "/health");
        var response = new MockHttpServletResponse();
        request.addHeader("X-Request-Id", "bad\nheader");

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("X-Request-Id")).isNotBlank();
        assertThat(response.getHeader("X-Request-Id")).doesNotContain("\n");
    }
}
