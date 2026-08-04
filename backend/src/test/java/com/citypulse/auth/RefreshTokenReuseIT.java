package com.citypulse.auth;

import com.citypulse.audit.domain.AuditAction;
import com.citypulse.audit.repository.AuditLogRepository;
import com.citypulse.auth.dto.AuthRequests;
import com.citypulse.auth.repository.RefreshTokenRepository;
import com.citypulse.support.IntegrationTest;
import com.citypulse.user.domain.RoleName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Refresh token reuse detection (docs/SECURITY.md §2).
 *
 * <p>These tests exist because of a real defect found during development: the
 * family revocation was originally performed inside the same transaction as the
 * rejection, so the {@code throw} that reported the reuse also rolled the
 * revocation back. The attacker was told "no" while the stolen token's
 * replacement remained valid — the mechanism appeared to work and did nothing.
 * The fix commits the revocation in a separate transaction; the third test below
 * is what proves it.
 */
@DisplayName("Refresh token reuse detection")
class RefreshTokenReuseIT extends IntegrationTest {

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    @DisplayName("a consumed refresh token cannot be exchanged a second time")
    void consumedTokenIsRejected() throws Exception {
        Tokens tokens = loginAs("reuse1@example.com", RoleName.VIEWER);

        mockMvc.perform(post("/api/v1/auth/refresh", new AuthRequests.Refresh(tokens.refreshToken())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/refresh", new AuthRequests.Refresh(tokens.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("reuse revokes the whole family, killing the legitimate replacement token too")
    void reuseRevokesEntireFamily() throws Exception {
        Tokens tokens = loginAs("reuse2@example.com", RoleName.VIEWER);

        // The legitimate client rotates once and holds the replacement.
        String body = mockMvc.perform(post("/api/v1/auth/refresh",
                        new AuthRequests.Refresh(tokens.refreshToken())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replacement = objectMapper.readTree(body).path("data").path("refreshToken").asText();

        // An attacker replays the captured original.
        mockMvc.perform(post("/api/v1/auth/refresh", new AuthRequests.Refresh(tokens.refreshToken())))
                .andExpect(status().isUnauthorized());

        // The replacement must now be dead as well. If the revocation were rolled
        // back by the rejection, this request would succeed — which is exactly the
        // bug this asserts against.
        mockMvc.perform(post("/api/v1/auth/refresh", new AuthRequests.Refresh(replacement)))
                .andExpect(status().isUnauthorized());

        assertThat(refreshTokenRepository.findAll())
                .as("every token in the family must be revoked")
                .allSatisfy(token -> assertThat(token.isRevoked()).isTrue());
    }

    @Test
    @DisplayName("the revocation survives the rejection, i.e. it is committed not rolled back")
    void revocationIsCommitted() throws Exception {
        Tokens tokens = loginAs("reuse3@example.com", RoleName.VIEWER);

        mockMvc.perform(post("/api/v1/auth/refresh", new AuthRequests.Refresh(tokens.refreshToken())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/refresh", new AuthRequests.Refresh(tokens.refreshToken())))
                .andExpect(status().isUnauthorized());

        // Read committed state directly, independent of any test-side transaction.
        long unrevoked = refreshTokenRepository.findAll().stream()
                .filter(token -> !token.isRevoked())
                .count();
        assertThat(unrevoked)
                .as("no token may remain usable after reuse is detected")
                .isZero();
    }

    @Test
    @DisplayName("reuse is recorded in the audit log as a security event")
    void reuseIsAudited() throws Exception {
        Tokens tokens = loginAs("reuse4@example.com", RoleName.VIEWER);

        mockMvc.perform(post("/api/v1/auth/refresh", new AuthRequests.Refresh(tokens.refreshToken())));
        mockMvc.perform(post("/api/v1/auth/refresh", new AuthRequests.Refresh(tokens.refreshToken())));

        assertThat(auditLogRepository.findAll())
                .anySatisfy(entry -> assertThat(entry.getAction()).isEqualTo(AuditAction.TOKEN_REUSE_DETECTED));
    }

    @Test
    @DisplayName("an unissued refresh token is rejected without revealing anything")
    void unknownTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh",
                        new AuthRequests.Refresh("this-token-was-never-issued-by-us")))
                .andExpect(status().isUnauthorized());
    }
}
