package com.citypulse.auth;

import com.citypulse.audit.domain.AuditAction;
import com.citypulse.audit.repository.AuditLogRepository;
import com.citypulse.auth.dto.AuthRequests;
import com.citypulse.support.IntegrationTest;
import com.citypulse.user.domain.RoleName;
import com.citypulse.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The critical user flow from PRD §36: signup → login → access a protected
 * endpoint → refresh → logout.
 */
@DisplayName("Authentication flow")
class AuthFlowIT extends IntegrationTest {

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    @DisplayName("signup creates an active VIEWER and never returns the password hash")
    void signupCreatesViewer() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup", new AuthRequests.Signup(
                        "newuser@example.com", VALID_PASSWORD, "New User", "Acme Corp")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("newuser@example.com"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                // Nothing password-shaped may appear anywhere in the response.
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.password").doesNotExist());

        User created = userRepository.findByEmailAndDeletedAtIsNull("newuser@example.com").orElseThrow();
        assertThat(created.roleNames()).containsExactly(RoleName.VIEWER);
        assertThat(created.getPasswordHash())
                .as("password must be stored as a BCrypt hash, never in plain text")
                .startsWith("$2")
                .isNotEqualTo(VALID_PASSWORD);
    }

    @Test
    @DisplayName("email is normalised to lower case, so casing cannot create a duplicate account")
    void emailIsCaseInsensitive() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup", new AuthRequests.Signup(
                        "MixedCase@Example.COM", VALID_PASSWORD, "Mixed Case", null)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/signup", new AuthRequests.Signup(
                        "mixedcase@example.com", VALID_PASSWORD, "Duplicate", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_CONFLICT"));

        // And the original address still authenticates regardless of the casing used.
        mockMvc.perform(post("/api/v1/auth/login",
                        new AuthRequests.Login("MIXEDCASE@EXAMPLE.COM", VALID_PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("login returns tokens plus the caller's profile and permissions")
    void loginReturnsTokensAndProfile() throws Exception {
        createUser("operator@example.com", RoleName.CITY_OPERATOR);

        mockMvc.perform(post("/api/v1/auth/login",
                        new AuthRequests.Login("operator@example.com", VALID_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andExpect(jsonPath("$.data.user.roles[0]").value(RoleName.CITY_OPERATOR))
                .andExpect(jsonPath("$.data.user.permissions").isNotEmpty());
    }

    @Test
    @DisplayName("the access token grants entry to a protected endpoint")
    void accessTokenWorks() throws Exception {
        Tokens tokens = loginAs("viewer@example.com", RoleName.VIEWER);

        mockMvc.perform(authGet("/api/v1/auth/me", tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("viewer@example.com"))
                .andExpect(jsonPath("$.data.roles[0]").value(RoleName.VIEWER));
    }

    @Test
    @DisplayName("refresh issues a new token pair, and the old refresh token stops working")
    void refreshRotatesToken() throws Exception {
        Tokens tokens = loginAs("rotate@example.com", RoleName.VIEWER);

        String body = mockMvc.perform(post("/api/v1/auth/refresh",
                        new AuthRequests.Refresh(tokens.refreshToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String rotated = objectMapper.readTree(body).path("data").path("refreshToken").asText();
        assertThat(rotated)
                .as("rotation must issue a different refresh token")
                .isNotEqualTo(tokens.refreshToken());

        // The consumed token is rejected (and, per RefreshTokenReuseIT, also
        // revokes the family).
        mockMvc.perform(post("/api/v1/auth/refresh", new AuthRequests.Refresh(tokens.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("logout revokes the session, so the refresh token can no longer be exchanged")
    void logoutRevokesSession() throws Exception {
        Tokens tokens = loginAs("logout@example.com", RoleName.VIEWER);

        mockMvc.perform(post("/api/v1/auth/logout", new AuthRequests.Logout(tokens.refreshToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(post("/api/v1/auth/refresh", new AuthRequests.Refresh(tokens.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("logout with an unknown token still reports success, so it cannot probe validity")
    void logoutIsUniform() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout",
                        new AuthRequests.Logout("a-token-that-was-never-issued")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("changing the password revokes every existing session")
    void passwordChangeRevokesSessions() throws Exception {
        Tokens tokens = loginAs("changepw@example.com", RoleName.VIEWER);
        String newPassword = "Even!Str0nger#Pass2026";

        mockMvc.perform(authPost("/api/v1/auth/change-password", tokens.accessToken(),
                        new AuthRequests.ChangePassword(VALID_PASSWORD, newPassword)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/refresh", new AuthRequests.Refresh(tokens.refreshToken())))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/login",
                        new AuthRequests.Login("changepw@example.com", newPassword)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("change-password rejects a wrong current password")
    void changePasswordRequiresCurrent() throws Exception {
        Tokens tokens = loginAs("wrongcurrent@example.com", RoleName.VIEWER);

        mockMvc.perform(authPost("/api/v1/auth/change-password", tokens.accessToken(),
                        new AuthRequests.ChangePassword("NotTheCurrent!123", "Another!Str0ng#2026")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CURRENT_PASSWORD"));
    }

    @Test
    @DisplayName("authentication events are written to the audit log")
    void authEventsAreAudited() throws Exception {
        createUser("audited@example.com", RoleName.VIEWER);
        login("audited@example.com", VALID_PASSWORD);
        mockMvc.perform(post("/api/v1/auth/login",
                new AuthRequests.Login("audited@example.com", "TotallyWrong!123")));

        var actions = auditLogRepository.findAll().stream().map(entry -> entry.getAction()).toList();
        assertThat(actions).contains(AuditAction.LOGIN_SUCCESS, AuditAction.LOGIN_FAILURE);

        // No audit entry may carry a credential. Detail is optional, so only
        // populated values are inspected.
        assertThat(auditLogRepository.findAll())
                .allSatisfy(entry -> {
                    if (entry.getDetail() != null) {
                        assertThat(entry.getDetail())
                                .as("audit detail must never contain a password")
                                .doesNotContain(VALID_PASSWORD);
                    }
                });
    }

    @Test
    @DisplayName("every response uses the standard envelope, including errors")
    void responseEnvelopeIsConsistent() throws Exception {
        mockMvc.perform(get("/api/v1/cities"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").isNotEmpty())
                .andExpect(jsonPath("$.error.message").isNotEmpty())
                .andExpect(jsonPath("$.error.requestId").isNotEmpty())
                // No internal detail may leak into an error body.
                .andExpect(jsonPath("$.error.stackTrace").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist());
    }
}
