package com.citypulse.auth.controller;

import com.citypulse.auth.dto.AuthRequests;
import com.citypulse.auth.dto.AuthResponses;
import com.citypulse.auth.service.AuthService;
import com.citypulse.common.api.ApiResponse;
import com.citypulse.security.CurrentUser;
import com.citypulse.user.dto.UserProfileResponse;
import com.citypulse.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints (PRD §7).
 *
 * <p>Controllers here validate, delegate, and map. All decisions live in
 * {@link AuthService} (docs/ARCHITECTURE.md §4).
 */
@RestController
@RequestMapping("/api/v1/auth")
@Validated
@Tag(name = "Authentication", description = "Signup, sign-in, session and credential management")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;
    private final CurrentUser currentUser;

    public AuthController(AuthService authService, UserService userService, CurrentUser currentUser) {
        this.authService = authService;
        this.userService = userService;
        this.currentUser = currentUser;
    }

    @PostMapping("/signup")
    @Operation(summary = "Create an account",
            description = "Creates an account with the VIEWER role. Elevation is an administrator action.")
    public ResponseEntity<ApiResponse<AuthResponses.SignupResult>> signup(
            @Valid @RequestBody AuthRequests.Signup request) {
        AuthResponses.SignupResult result = authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(result, result.message()));
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in",
            description = "Returns an access token, a refresh token, and the caller's profile.")
    public ResponseEntity<ApiResponse<AuthResponses.Tokens>> login(
            @Valid @RequestBody AuthRequests.Login request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(authService.login(request, httpRequest), "Signed in successfully"));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token",
            description = "Single-use. The presented token is consumed and a replacement is issued. "
                          + "Re-presenting a consumed token revokes the entire session family.")
    public ResponseEntity<ApiResponse<AuthResponses.Tokens>> refresh(
            @Valid @RequestBody AuthRequests.Refresh request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(authService.refresh(request, httpRequest), "Session refreshed"));
    }

    @PostMapping("/logout")
    @Operation(summary = "Sign out",
            description = "Revokes the session family. Always reports success, so an unauthenticated "
                          + "caller cannot use it to test whether a token is valid.")
    public ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody AuthRequests.Logout request) {
        authService.logout(request);
        return ResponseEntity.ok(ApiResponse.ok("Signed out successfully"));
    }

    @PostMapping("/forgot-password")
    @Operation(summary = "Request a password reset",
            description = "Always reports success, whether or not the address is registered, "
                          + "so the endpoint cannot be used to discover accounts.")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(
            @Valid @RequestBody AuthRequests.ForgotPassword request) {
        authService.requestPasswordReset(request);
        return ResponseEntity.ok(ApiResponse.ok(
                "If an account exists for that address, a reset link has been generated."));
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Complete a password reset",
            description = "Consumes the reset token and revokes every existing session.")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @Valid @RequestBody AuthRequests.ResetPassword request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(ApiResponse.ok("Password updated. Sign in with your new password."));
    }

    @GetMapping("/verify-email")
    @Operation(summary = "Verify an email address", description = "Consumes the verification token.")
    public ResponseEntity<ApiResponse<Void>> verifyEmail(@RequestParam @NotBlank String token) {
        authService.verifyEmail(token);
        return ResponseEntity.ok(ApiResponse.ok("Email verified. You can now sign in."));
    }

    @PostMapping("/change-password")
    @Operation(summary = "Change your password",
            description = "Requires the current password. Revokes every existing session on success.")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @Valid @RequestBody AuthRequests.ChangePassword request) {
        authService.changePassword(currentUser.require().userUid(), request);
        return ResponseEntity.ok(ApiResponse.ok("Password changed. Please sign in again."));
    }

    @GetMapping("/me")
    @Operation(summary = "Get the authenticated user's profile",
            description = "Read from the database rather than token claims, so role changes are "
                          + "reflected without waiting for the access token to expire.")
    public ResponseEntity<ApiResponse<UserProfileResponse>> me() {
        return ResponseEntity.ok(ApiResponse.ok(userService.getProfile(currentUser.require().userUid())));
    }
}
