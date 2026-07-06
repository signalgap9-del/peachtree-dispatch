package com.atmospath.platform.api;

import java.util.HashMap;
import java.util.Map;

import com.atmospath.platform.account.QuotaExceededException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalApiExceptionHandler {
    @ExceptionHandler(QuotaExceededException.class)
    ResponseEntity<ApiErrorResponse> quotaExceeded(QuotaExceededException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(error("QUOTA_EXCEEDED", exception.getMessage(), Map.of(
                        "feature", exception.feature(),
                        "plan", exception.plan(),
                        "used", exception.used(),
                        "limit", exception.limit(),
                        "resetsAt", exception.resetsAt(),
                        "upgradePath", "/pricing"), request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> invalidBody(MethodArgumentNotValidException exception, HttpServletRequest request) {
        var details = new HashMap<String, Object>();
        details.put("fieldErrors", exception.getBindingResult().getFieldErrors().stream()
                .map(field -> Map.of("field", field.getField(), "message", field.getDefaultMessage()))
                .toList());
        return ResponseEntity.unprocessableEntity()
                .body(error("VALIDATION_ERROR", "Request body failed validation.", details, request));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiErrorResponse> invalidParameter(ConstraintViolationException exception, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(error("VALIDATION_ERROR", "Request parameters failed validation.", Map.of("violations",
                        exception.getConstraintViolations().stream().map(violation -> Map.of(
                                "path", violation.getPropertyPath().toString(),
                                "message", violation.getMessage())).toList()), request));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiErrorResponse> responseStatus(ResponseStatusException exception, HttpServletRequest request) {
        var status = HttpStatus.valueOf(exception.getStatusCode().value());
        return ResponseEntity.status(status)
                .body(error(status.name(), exception.getReason() == null ? status.getReasonPhrase() : exception.getReason(),
                        Map.of(), request));
    }

    private ApiErrorResponse error(String code, String message, Map<String, Object> details, HttpServletRequest request) {
        var requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) {
            requestId = request.getHeader("X-Request-Id");
        }
        return new ApiErrorResponse(new ApiErrorResponse.ApiError(code, message, requestId, details));
    }
}
