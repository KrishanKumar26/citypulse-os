package com.citypulse.security.config;

import com.citypulse.common.api.ApiError;
import com.citypulse.common.api.ApiResponse;
import com.citypulse.common.web.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Returns the standard error envelope for unauthenticated requests. Without
 * this, Spring Security emits its own HTML/JSON error page, breaking the
 * contract that every response uses one shape (PRD §28).
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        ApiError error = ApiError.of("UNAUTHENTICATED",
                "Authentication is required to access this resource",
                MDC.get(RequestIdFilter.REQUEST_ID_KEY));
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(error));
    }
}
