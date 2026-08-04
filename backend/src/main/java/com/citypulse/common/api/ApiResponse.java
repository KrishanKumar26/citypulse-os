package com.citypulse.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The single response envelope for every endpoint (PRD §28).
 *
 * <p>Exactly one of {@code data} or {@code error} is populated. Serialisation
 * omits nulls, so a success response carries no {@code error} key at all.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        T data,
        String message,
        ApiError error
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, "Request successful", null);
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(true, data, message, null);
    }

    public static ApiResponse<Void> ok(String message) {
        return new ApiResponse<>(true, null, message, null);
    }

    public static <T> ApiResponse<T> failure(ApiError error) {
        return new ApiResponse<>(false, null, null, error);
    }
}
