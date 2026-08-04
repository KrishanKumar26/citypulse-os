package com.citypulse.auth.dto;

import com.citypulse.common.validation.StrongPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payloads for the auth module. Records, so they are immutable and
 * cannot be accidentally bound to an entity (PRD §26 — no entity exposure, and
 * no mass assignment).
 */
public final class AuthRequests {

    private AuthRequests() {
    }

    public record Signup(
            @NotBlank(message = "Email is required")
            @Email(message = "Email must be a valid address")
            @Size(max = 255, message = "Email must not exceed 255 characters")
            String email,

            @NotBlank(message = "Password is required")
            @StrongPassword
            String password,

            @NotBlank(message = "Full name is required")
            @Size(min = 2, max = 120, message = "Full name must be between 2 and 120 characters")
            String fullName,

            @Size(max = 160, message = "Organization must not exceed 160 characters")
            String organization
    ) {
    }

    public record Login(
            @NotBlank(message = "Email is required")
            @Email(message = "Email must be a valid address")
            String email,

            // Deliberately no @StrongPassword: an existing password predating a
            // policy change must still be able to authenticate, and echoing
            // policy details on login would leak the rules to an attacker.
            @NotBlank(message = "Password is required")
            String password
    ) {
    }

    public record Refresh(
            @NotBlank(message = "Refresh token is required")
            String refreshToken
    ) {
    }

    public record Logout(
            @NotBlank(message = "Refresh token is required")
            String refreshToken
    ) {
    }

    public record ForgotPassword(
            @NotBlank(message = "Email is required")
            @Email(message = "Email must be a valid address")
            String email
    ) {
    }

    public record ResetPassword(
            @NotBlank(message = "Reset token is required")
            String token,

            @NotBlank(message = "New password is required")
            @StrongPassword
            String newPassword
    ) {
    }

    public record ChangePassword(
            @NotBlank(message = "Current password is required")
            String currentPassword,

            @NotBlank(message = "New password is required")
            @StrongPassword
            String newPassword
    ) {
    }
}
