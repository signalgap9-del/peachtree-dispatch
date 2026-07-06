package com.atmospath.platform.api;

import java.util.Map;

public record ApiErrorResponse(ApiError error) {
    public record ApiError(String code, String message, String requestId, Map<String, Object> details) {
    }
}
