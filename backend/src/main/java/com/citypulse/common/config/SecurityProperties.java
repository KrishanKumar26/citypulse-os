package com.citypulse.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Security configuration, bound from {@code citypulse.security.*}.
 *
 * <p>Validated at startup: an invalid or missing value stops the application
 * rather than letting it run with a weak default (docs/SECURITY.md §1).
 */
@ConfigurationProperties(prefix = "citypulse.security")
@Validated
public record SecurityProperties(
        @Valid @NotNull Jwt jwt,
        @Valid @NotNull Refresh refresh,
        @Valid @NotNull Lockout lockout,
        @Valid @NotNull RateLimit rateLimit,
        @Valid @NotNull PasswordReset passwordReset,
        @Valid @NotNull Signup signup,
        @Valid @NotNull Cors cors
) {

    /**
     * @param requireEmailVerification when true, a new account stays
     *                                 {@code PENDING_VERIFICATION} and cannot log in
     *                                 until the emailed link is redeemed. Disabled in
     *                                 local development because no mail provider is
     *                                 configured; enabled in the production profile
     * @param verificationTokenTtl     lifetime of the verification link
     */
    public record Signup(
            boolean requireEmailVerification,
            @NotNull Duration verificationTokenTtl
    ) {
    }

    /**
     * @param secret HMAC signing key. Supplied only through the environment; there is
     *               no default, so a misconfigured deployment fails loudly instead of
     *               signing tokens with a guessable key. Must be at least 32 bytes
     *               to satisfy HS256.
     */
    public record Jwt(
            @NotBlank String secret,
            @NotBlank String issuer,
            @NotNull Duration accessTokenTtl
    ) {
    }

    public record Refresh(
            @NotNull Duration ttl,
            /* Cap on simultaneously usable refresh tokens per user, bounding how
               many devices a stolen credential could keep alive. */
            @Min(1) int maxActiveSessionsPerUser
    ) {
    }

    /**
     * Account lockout after consecutive failed logins. Slows credential stuffing
     * without permanently locking a user out of their own account.
     */
    public record Lockout(
            @Min(1) int maxFailedAttempts,
            @NotNull Duration duration
    ) {
    }

    public record RateLimit(
            @Min(1) int authRequestsPerMinute,
            boolean enabled
    ) {
    }

    public record PasswordReset(
            @NotNull Duration tokenTtl
    ) {
    }

    /**
     * @param allowedOrigins exact origins only. A wildcard is rejected at startup
     *                       because it cannot be combined with credentials and
     *                       would silently disable the protection.
     */
    public record Cors(
            @NotNull List<String> allowedOrigins,
            @NotNull List<String> allowedMethods,
            @NotNull Duration maxAge
    ) {
    }
}
