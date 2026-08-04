package com.citypulse.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Client-facing error detail. Never carries stack traces, SQL, or internal
 * class names (PRD §28, docs/SECURITY.md §4).
 *
 * @param code       stable machine-readable code, e.g. {@code RESOURCE_NOT_FOUND}
 * @param message    human-readable, safe to display
 * @param fieldErrors per-field validation messages, only for validation failures
 * @param requestId  correlation id, also returned in the {@code X-Request-Id} header
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String code,
        String message,
        List<FieldError> fieldErrors,
        String requestId,
        Instant timestamp
) {

    public record FieldError(String field, String message) {
    }

    public static ApiError of(String code, String message, String requestId) {
        return new ApiError(code, message, null, requestId, Instant.now());
    }

    public static ApiError validation(String message, Map<String, String> errors, String requestId) {
        List<FieldError> fields = errors.entrySet().stream()
                .map(e -> new FieldError(e.getKey(), e.getValue()))
                .toList();
        return new ApiError("VALIDATION_FAILED", message, fields, requestId, Instant.now());
    }
}
