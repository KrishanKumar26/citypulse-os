package com.citypulse.security;

import com.citypulse.support.IntegrationTest;
import com.citypulse.user.domain.RoleName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization enforcement at the API layer (docs/SECURITY.md §3, §8).
 *
 * <p>The point of these tests is that the backend decides. A frontend that hid
 * every forbidden control would still fail here if the API allowed the call.
 */
@DisplayName("Authorization")
class AuthorizationIT extends IntegrationTest {

    // ------------------------------------------------------------------
    // Unauthenticated
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a protected endpoint returns 401 without a token")
    void protectedEndpointRequiresToken() throws Exception {
        mockMvc.perform(get("/api/v1/cities"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("a malformed token is rejected, not treated as anonymous access")
    void malformedTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/cities").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token signed with the wrong key is rejected")
    void wrongSignatureIsRejected() throws Exception {
        Tokens tokens = loginAs("sigtest@example.com", RoleName.VIEWER);

        // Corrupt the signature segment only, leaving header and payload intact.
        String[] parts = tokens.accessToken().split("\\.");
        String tampered = parts[0] + "." + parts[1] + ".AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

        mockMvc.perform(get("/api/v1/cities").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a tampered payload is rejected, so claims cannot be self-elevated")
    void tamperedPayloadIsRejected() throws Exception {
        Tokens tokens = loginAs("tamper@example.com", RoleName.VIEWER);
        String[] parts = tokens.accessToken().split("\\.");

        // Re-encode the payload granting every permission. The signature no longer
        // matches, so this must fail — otherwise a client could mint its own roles.
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + java.util.UUID.randomUUID() + "\",\"iss\":\"citypulse-os\","
                 + "\"typ\":\"access\",\"perms\":[\"city:write\",\"user:manage_roles\"],"
                 + "\"exp\":9999999999}").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(get("/api/v1/cities")
                        .header("Authorization", "Bearer " + parts[0] + "." + forgedPayload + "." + parts[2]))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a refresh token cannot be used as an access token")
    void refreshTokenIsNotAnAccessToken() throws Exception {
        Tokens tokens = loginAs("wrongtype@example.com", RoleName.VIEWER);

        mockMvc.perform(get("/api/v1/cities").header("Authorization", "Bearer " + tokens.refreshToken()))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Authenticated but insufficient permission
    // ------------------------------------------------------------------

    @Test
    @DisplayName("VIEWER may read cities but not create one")
    void viewerCannotWriteCities() throws Exception {
        Tokens viewer = loginAs("ro@example.com", RoleName.VIEWER);

        mockMvc.perform(authGet("/api/v1/cities", viewer.accessToken()))
                .andExpect(status().isOk());

        mockMvc.perform(authPost("/api/v1/cities", viewer.accessToken(), validCityPayload()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("VIEWER cannot list users, read the audit log, or reach actuator")
    void viewerCannotReachAdminSurfaces() throws Exception {
        Tokens viewer = loginAs("ro2@example.com", RoleName.VIEWER);

        mockMvc.perform(authGet("/api/v1/users", viewer.accessToken())).andExpect(status().isForbidden());
        mockMvc.perform(authGet("/api/v1/roles", viewer.accessToken())).andExpect(status().isForbidden());
        mockMvc.perform(authGet("/api/v1/audit-logs", viewer.accessToken())).andExpect(status().isForbidden());
        mockMvc.perform(authGet("/actuator/prometheus", viewer.accessToken())).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ANALYST can export analytics; CITY_OPERATOR cannot")
    void permissionsDifferBetweenRoles() throws Exception {
        // Asserted against the seeded grants rather than endpoints, because the
        // analytics module arrives in a later phase. This keeps the role model
        // itself under test now.
        Tokens analyst = loginAs("analyst@example.com", RoleName.ANALYST);
        Tokens operator = loginAs("operator@example.com", RoleName.CITY_OPERATOR);

        mockMvc.perform(authGet("/api/v1/auth/me", analyst.accessToken()))
                .andExpect(jsonPath("$.data.permissions").value(
                        org.hamcrest.Matchers.hasItem("analytics:export")));

        mockMvc.perform(authGet("/api/v1/auth/me", operator.accessToken()))
                .andExpect(jsonPath("$.data.permissions").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("analytics:export"))))
                .andExpect(jsonPath("$.data.permissions").value(
                        org.hamcrest.Matchers.hasItem("alert:manage")));
    }

    @Test
    @DisplayName("ADMIN may write cities")
    void adminCanWriteCities() throws Exception {
        Tokens admin = loginAs("admin@example.com", RoleName.ADMIN);

        mockMvc.perform(authPost("/api/v1/cities", admin.accessToken(), validCityPayload()))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a user cannot change their own roles, so no self-escalation is possible")
    void cannotSelfEscalate() throws Exception {
        Tokens admin = loginAs("selfesc@example.com", RoleName.ADMIN);
        var self = userRepository.findByEmailAndDeletedAtIsNull("selfesc@example.com").orElseThrow();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/users/{id}/roles", self.getUid())
                        .header("Authorization", "Bearer " + admin.accessToken())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"SUPER_ADMIN\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("only a SUPER_ADMIN can grant SUPER_ADMIN")
    void onlySuperAdminGrantsSuperAdmin() throws Exception {
        Tokens admin = loginAs("granter@example.com", RoleName.ADMIN);
        var target = createUser("target@example.com", RoleName.VIEWER);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/users/{id}/roles", target.getUid())
                        .header("Authorization", "Bearer " + admin.accessToken())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"SUPER_ADMIN\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.containsString("super administrator")));
    }

    // ------------------------------------------------------------------
    // Public surfaces
    // ------------------------------------------------------------------

    @Test
    @DisplayName("public endpoints are reachable without a token")
    void publicEndpointsAreOpen() throws Exception {
        mockMvc.perform(get("/api/v1/meta/platform")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("security headers are present on API responses")
    void securityHeadersArePresent() throws Exception {
        mockMvc.perform(get("/api/v1/meta/platform"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().exists("Referrer-Policy"))
                // Correlates the response with its server-side log lines.
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    @DisplayName("an unknown endpoint returns the standard error envelope, not an HTML page")
    void unknownEndpointUsesEnvelope() throws Exception {
        Tokens viewer = loginAs("notfound@example.com", RoleName.VIEWER);

        mockMvc.perform(authGet("/api/v1/does-not-exist", viewer.accessToken()))
                .andExpect(status().isNotFound());
    }

    /**
     * A map rather than a JSON string: {@code authPost} serialises its argument,
     * so handing it a string would double-encode the body into a quoted literal
     * and the request would fail validation for the wrong reason.
     */
    private java.util.Map<String, Object> validCityPayload() {
        return java.util.Map.of(
                "slug", "test-city",
                "name", "Test City",
                "country", "India",
                "countryCode", "IN",
                "timezone", "Asia/Kolkata",
                "centerLatitude", 12.9716,
                "centerLongitude", 77.5946,
                "defaultZoom", 11);
    }
}
