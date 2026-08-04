package com.citypulse.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Base for all deliberately-thrown domain exceptions. Carries the HTTP status
 * and the stable error code so {@link GlobalExceptionHandler} never has to
 * guess, and so no unexpected exception can leak its message to a client.
 */
public abstract class ApplicationException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApplicationException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
