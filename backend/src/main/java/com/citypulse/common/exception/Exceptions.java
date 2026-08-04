package com.citypulse.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Concrete domain exceptions, grouped so the set stays small and reviewable.
 */
public final class Exceptions {

    private Exceptions() {
    }

    /** Requested resource does not exist, or the caller may not know that it does. */
    public static class NotFound extends ApplicationException {
        public NotFound(String resource, Object identifier) {
            super(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                    "%s '%s' was not found".formatted(resource, identifier));
        }

        public NotFound(String message) {
            super(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", message);
        }
    }

    /** The request conflicts with current state, e.g. a duplicate unique value. */
    public static class Conflict extends ApplicationException {
        public Conflict(String message) {
            super(HttpStatus.CONFLICT, "RESOURCE_CONFLICT", message);
        }
    }

    /** The request is well-formed but semantically invalid. */
    public static class BadRequest extends ApplicationException {
        public BadRequest(String code, String message) {
            super(HttpStatus.BAD_REQUEST, code, message);
        }

        public BadRequest(String message) {
            super(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
        }
    }

    /**
     * Authentication failed. The message is deliberately uniform for every cause
     * so responses cannot be used to enumerate accounts (docs/SECURITY.md §2).
     */
    public static class InvalidCredentials extends ApplicationException {
        public InvalidCredentials() {
            super(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
        }
    }

    /** Token missing, expired, malformed, or already consumed. */
    public static class InvalidToken extends ApplicationException {
        public InvalidToken(String message) {
            super(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", message);
        }
    }

    /** Authenticated but lacking the required permission. */
    public static class Forbidden extends ApplicationException {
        public Forbidden(String message) {
            super(HttpStatus.FORBIDDEN, "ACCESS_DENIED", message);
        }
    }

    /** The account exists but cannot be used for authentication. */
    public static class AccountUnavailable extends ApplicationException {
        public AccountUnavailable(String message) {
            super(HttpStatus.FORBIDDEN, "ACCOUNT_UNAVAILABLE", message);
        }
    }

    /** Client exceeded a rate limit. */
    public static class RateLimited extends ApplicationException {
        public RateLimited(String message) {
            super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", message);
        }
    }
}
