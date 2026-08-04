package com.citypulse.auth.dto;

import com.citypulse.user.dto.UserProfileResponse;

import java.time.Instant;

public final class AuthResponses {

    private AuthResponses() {
    }

    /**
     * Issued credentials plus the caller's profile, so the client needs no second
     * request to render the shell after login.
     *
     * <p>Tokens are returned in the body rather than set as cookies: the API is
     * consumed cross-origin by the Next.js app and by third-party API clients,
     * and a bearer scheme keeps one code path for both. The trade-off is that the
     * client must store the access token in memory, never in {@code localStorage}
     * — enforced in the frontend API client.
     */
    public record Tokens(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresIn,
            Instant accessTokenExpiresAt,
            UserProfileResponse user
    ) {
        public static Tokens bearer(String accessToken, String refreshToken, long expiresIn,
                                    Instant expiresAt, UserProfileResponse user) {
            return new Tokens(accessToken, refreshToken, "Bearer", expiresIn, expiresAt, user);
        }
    }

    /**
     * Returned by signup. Carries no tokens: an account starts as
     * {@code PENDING_VERIFICATION} and cannot authenticate until verified.
     */
    public record SignupResult(
            String email,
            String status,
            boolean emailVerificationRequired,
            String message
    ) {
    }
}
