package com.atmospath.platform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;

import com.atmospath.platform.account.MeteredFeature;
import com.atmospath.platform.account.PlanCode;
import com.atmospath.platform.account.QuotaExceededException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class GlobalApiExceptionHandlerTests {

    private GlobalApiExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalApiExceptionHandler();
        request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Request-Id")).thenReturn("req-123");
    }

    @Test
    void quotaExceededReturns429WithDetails() {
        QuotaExceededException ex = new QuotaExceededException(
                MeteredFeature.ROUTE_PLAN, PlanCode.FREE, 101, 100, "2026-07-28T00:00:00Z");

        ResponseEntity<ApiErrorResponse> response = handler.quotaExceeded(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("QUOTA_EXCEEDED");
        assertThat(response.getBody().error().details()).containsKey("feature");
        assertThat(response.getBody().error().details()).containsKey("upgradePath");
    }

    @Test
    void responseStatusExceptionMapsToCorrectStatus() {
        ResponseStatusException ex = new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Route not found");

        ResponseEntity<ApiErrorResponse> response = handler.responseStatus(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error().code()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().error().message()).isEqualTo("Route not found");
    }

    @Test
    void responseStatusWithoutReasonUsesDefault() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.FORBIDDEN);

        ResponseEntity<ApiErrorResponse> response = handler.responseStatus(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().error().message()).isNotNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    void constraintViolationReturnsBadRequest() {
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("param.lat");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must be between -90 and 90");

        ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

        ResponseEntity<ApiErrorResponse> response = handler.invalidParameter(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
    }
}
