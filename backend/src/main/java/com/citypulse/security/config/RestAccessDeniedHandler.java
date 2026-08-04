package com.citypulse.security.config;

import com.citypulse.common.api.ApiError;
import com.citypulse.common.api.ApiResponse;
import com.citypulse.common.web.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Returns the standard error envelope for authenticated-but-unauthorised
 * requests, and logs the denial. The response says only that permission is
 * missing — never which permission would have been sufficient, which would let a
 * caller map the permission model by probing.
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(RestAccessDeniedHandler.class);

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException ex) throws IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        log.warn("Access denied for principal '{}' on {} {}",
                authentication != null ? authentication.getName() : "anonymous",
                request.getMethod(), request.getRequestURI());

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        ApiError error = ApiError.of("ACCESS_DENIED",
                "You do not have permission to perform this action",
                MDC.get(RequestIdFilter.REQUEST_ID_KEY));
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(error));
    }
}
