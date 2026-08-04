package com.citypulse.common.exception;

import com.citypulse.common.api.ApiError;
import com.citypulse.common.api.ApiResponse;
import com.citypulse.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts every exception into the standard error envelope.
 *
 * <p>Two rules hold throughout: clients never receive a stack trace, an SQL
 * fragment, or an internal class name; and every 5xx is logged with its full
 * cause server-side so nothing is silently swallowed.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ApiResponse<Void>> handleApplication(ApplicationException ex) {
        // Expected domain outcomes: log at debug, they are not defects.
        log.debug("Domain exception [{}]: {}", ex.getCode(), ex.getMessage());
        return build(ex.getStatus(), ApiError.of(ex.getCode(), ex.getMessage(), requestId()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleBodyValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> errors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        ex.getBindingResult().getGlobalErrors()
                .forEach(ge -> errors.putIfAbsent(ge.getObjectName(), ge.getDefaultMessage()));
        return build(HttpStatus.UNPROCESSABLE_ENTITY,
                ApiError.validation("Request validation failed", errors, requestId()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleParamValidation(ConstraintViolationException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v ->
                errors.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
        return build(HttpStatus.UNPROCESSABLE_ENTITY,
                ApiError.validation("Request validation failed", errors, requestId()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        // The parser message can echo payload content; return a fixed message instead.
        log.debug("Unreadable request body: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST,
                ApiError.of("MALFORMED_REQUEST", "Request body is missing or malformed", requestId()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        return build(HttpStatus.BAD_REQUEST, ApiError.of("MISSING_PARAMETER",
                "Required parameter '%s' is missing".formatted(ex.getParameterName()), requestId()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return build(HttpStatus.BAD_REQUEST, ApiError.of("INVALID_PARAMETER",
                "Parameter '%s' has an invalid value".formatted(ex.getName()), requestId()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return build(HttpStatus.METHOD_NOT_ALLOWED,
                ApiError.of("METHOD_NOT_ALLOWED", "HTTP method is not supported for this endpoint", requestId()));
    }

    /**
     * Spring 6 raises {@link NoResourceFoundException} rather than
     * {@link NoHandlerFoundException} for an unmapped path. Both are handled:
     * without the former, an unknown URL falls through to the catch-all below and
     * is reported as a 500, which misrepresents a client mistake as a server
     * fault and pollutes error-rate metrics.
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNoHandler(Exception ex) {
        return build(HttpStatus.NOT_FOUND,
                ApiError.of("ENDPOINT_NOT_FOUND", "Requested endpoint was not found", requestId()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return build(HttpStatus.FORBIDDEN,
                ApiError.of("ACCESS_DENIED", "You do not have permission to perform this action", requestId()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException ex) {
        return build(HttpStatus.UNAUTHORIZED,
                ApiError.of("UNAUTHENTICATED", "Authentication is required to access this resource", requestId()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex) {
        // The driver message contains table, column, and constraint names — log it, never return it.
        log.warn("Data integrity violation", ex);
        return build(HttpStatus.CONFLICT,
                ApiError.of("RESOURCE_CONFLICT", "The request conflicts with existing data", requestId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                ApiError.of("INTERNAL_ERROR", "An unexpected error occurred", requestId()));
    }

    private ResponseEntity<ApiResponse<Void>> build(HttpStatus status, ApiError error) {
        return ResponseEntity.status(status).body(ApiResponse.failure(error));
    }

    private String requestId() {
        return MDC.get(RequestIdFilter.REQUEST_ID_KEY);
    }
}
