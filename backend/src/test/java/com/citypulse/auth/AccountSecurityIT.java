package com.citypulse.auth;

import com.citypulse.auth.dto.AuthRequests;
import com.citypulse.support.IntegrationTest;
import com.citypulse.user.domain.RoleName;
import com.citypulse.user.domain.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Account lockout and enumeration resistance (docs/SECURITY.md §2, §7).
 */
@DisplayName("Account security")
class AccountSecurityIT extends IntegrationTest {

    /** Matches {@code citypulse.security.lockout.max-failed-attempts} in the test profile. */
    private static final int MAX_ATTEMPTS = 5;

    @Test
    @DisplayName("the account locks after the configured number of failed attempts")
    void accountLocksAfterRepeatedFailures() throws Exception {
        createUser("lockme@example.com", RoleName.VIEWER);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login",
                            new AuthRequests.Login("lockme@example.com", "WrongPassword!123")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
        }

        // Even the correct password is refused while the lock holds. This is the
        // assertion that would have caught the original bug, where the attempt
        // counter was rolled back with the failed login transaction and the
        // account therefore never locked.
        mockMvc.perform(post("/api/v1/auth/login",
                        new AuthRequests.Login("lockme@example.com", VALID_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_UNAVAILABLE"));

        var locked = userRepository.findByEmailAndDeletedAtIsNull("lockme@example.com").orElseThrow();
        assertThat(locked.isLocked()).isTrue();
    }

    @Test
    @DisplayName("a successful login clears the failed-attempt counter")
    void successResetsCounter() throws Exception {
        createUser("resetcount@example.com", RoleName.VIEWER);

        for (int attempt = 0; attempt < MAX_ATTEMPTS - 1; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login",
                    new AuthRequests.Login("resetcount@example.com", "WrongPassword!123")));
        }

        mockMvc.perform(post("/api/v1/auth/login",
                        new AuthRequests.Login("resetcount@example.com", VALID_PASSWORD)))
                .andExpect(status().isOk());

        var user = userRepository.findByEmailAndDeletedAtIsNull("resetcount@example.com").orElseThrow();
        assertThat(user.getFailedLoginAttempts())
                .as("the counter must reset, so intermittent typos never accumulate into a lockout")
                .isZero();
        assertThat(user.isLocked()).isFalse();
    }

    @Test
    @DisplayName("an unknown address and a wrong password are indistinguishable")
    void loginDoesNotRevealWhetherAccountExists() throws Exception {
        createUser("known@example.com", RoleName.VIEWER);

        var wrongPassword = mockMvc.perform(post("/api/v1/auth/login",
                        new AuthRequests.Login("known@example.com", "WrongPassword!123")))
                .andReturn().getResponse();

        var unknownAccount = mockMvc.perform(post("/api/v1/auth/login",
                        new AuthRequests.Login("nobody@example.com", "WrongPassword!123")))
                .andReturn().getResponse();

        assertThat(unknownAccount.getStatus()).isEqualTo(wrongPassword.getStatus());
        // Every field except the generated timestamp and request id must match;
        // those two differ on any two requests and carry no information about
        // whether the account exists.
        assertThat(withoutVolatileFields(unknownAccount.getContentAsString()))
                .as("responses must be indistinguishable, or the endpoint enumerates accounts")
                .isEqualTo(withoutVolatileFields(wrongPassword.getContentAsString()));
    }

    @Test
    @DisplayName("forgot-password responds identically for known and unknown addresses")
    void passwordResetDoesNotRevealWhetherAccountExists() throws Exception {
        createUser("resetknown@example.com", RoleName.VIEWER);

        var known = mockMvc.perform(post("/api/v1/auth/forgot-password",
                        new AuthRequests.ForgotPassword("resetknown@example.com")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var unknown = mockMvc.perform(post("/api/v1/auth/forgot-password",
                        new AuthRequests.ForgotPassword("resetunknown@example.com")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(withoutVolatileFields(unknown)).isEqualTo(withoutVolatileFields(known));
    }

    /** Strips the generated timestamp and request id, which differ on every response. */
    private String withoutVolatileFields(String json) {
        return json
                .replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"<t>\"")
                .replaceAll("\"requestId\":\"[^\"]*\"", "\"requestId\":\"<id>\"");
    }

    @Test
    @DisplayName("a suspended account cannot sign in")
    void suspendedAccountCannotSignIn() throws Exception {
        var user = createUser("suspended@example.com", RoleName.VIEWER);
        transactionTemplate.executeWithoutResult(status -> {
            var managed = userRepository.findById(user.getId()).orElseThrow();
            managed.setStatus(UserStatus.SUSPENDED);
            userRepository.save(managed);
        });

        mockMvc.perform(post("/api/v1/auth/login",
                        new AuthRequests.Login("suspended@example.com", VALID_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_UNAVAILABLE"));
    }

    @Test
    @DisplayName("the password policy is enforced by the API, not only by the UI")
    void passwordPolicyIsEnforcedServerSide() throws Exception {
        // Each of these would pass a naive length-only check somewhere in a form.
        String[] rejected = {
                "short",                  // too short
                "alllowercase1234!",      // no uppercase
                "ALLUPPERCASE1234!",      // no lowercase
                "NoDigitsInHere!!!",      // no digit
                "NoSymbolsInHere123"      // no symbol
        };

        for (String weak : rejected) {
            mockMvc.perform(post("/api/v1/auth/signup", new AuthRequests.Signup(
                            "weak-" + weak.hashCode() + "@example.com", weak, "Weak Password", null)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    @DisplayName("a password reset consumes its token and revokes every session")
    void passwordResetIsSingleUseAndRevokesSessions() throws Exception {
        var user = createUser("resetflow@example.com", RoleName.VIEWER);
        Tokens session = login("resetflow@example.com", VALID_PASSWORD);

        mockMvc.perform(post("/api/v1/auth/forgot-password",
                        new AuthRequests.ForgotPassword("resetflow@example.com")))
                .andExpect(status().isOk());

        // The raw token is only ever sent to the user; the database holds its hash.
        // Issue a known token directly so the redemption path can be exercised.
        String rawToken = issueResetTokenFor(user.getId());
        String newPassword = "Rotated!Passw0rd#2026";

        mockMvc.perform(post("/api/v1/auth/reset-password",
                        new AuthRequests.ResetPassword(rawToken, newPassword)))
                .andExpect(status().isOk());

        // Single use.
        mockMvc.perform(post("/api/v1/auth/reset-password",
                        new AuthRequests.ResetPassword(rawToken, "Another!Passw0rd#2026")))
                .andExpect(status().isUnauthorized());

        // Pre-existing sessions are gone.
        mockMvc.perform(post("/api/v1/auth/refresh", new AuthRequests.Refresh(session.refreshToken())))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/login",
                        new AuthRequests.Login("resetflow@example.com", newPassword)))
                .andExpect(status().isOk());
    }

    /** Stores a reset token whose raw value the test knows, mirroring what the service does. */
    private String issueResetTokenFor(Long userId) {
        var hasher = webApplicationContext.getBean(com.citypulse.auth.service.TokenHasher.class);
        var repository = webApplicationContext.getBean(
                com.citypulse.auth.repository.UserTokenRepository.class);

        String raw = hasher.generateToken();
        transactionTemplate.executeWithoutResult(status -> {
            var token = new com.citypulse.auth.domain.UserToken();
            token.setUserId(userId);
            token.setTokenType(com.citypulse.auth.domain.UserTokenType.PASSWORD_RESET);
            token.setTokenHash(hasher.hash(raw));
            token.setExpiresAt(java.time.Instant.now().plusSeconds(600));
            repository.save(token);
        });
        return raw;
    }
}
