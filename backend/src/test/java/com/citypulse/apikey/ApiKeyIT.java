package com.citypulse.apikey;

import com.citypulse.support.IntegrationTest;
import com.citypulse.user.domain.RoleName;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API keys, and the two rules the feature's security rests on.
 *
 * <p>The secret is returned once and stored never; and a key cannot carry
 * authority its creator does not hold. Without the second, this endpoint is a
 * hole straight through the permission model.
 */
@DisplayName("API keys")
class ApiKeyIT extends IntegrationTest {

    private JsonNode create(String token, String body) throws Exception {
        return objectMapper.readTree(mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/api-keys")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("data");
    }

    @Test
    @DisplayName("returns the secret once, and never again")
    void secretIsShownOnlyAtCreation() throws Exception {
        Tokens tokens = loginAs("key-once@example.com", RoleName.CITY_OPERATOR);

        JsonNode created = create(tokens.accessToken(), """
                {"name":"Reporting job","scopes":["telemetry:read"]}
                """);
        String secret = created.path("secret").asText();
        assertThat(secret).startsWith("cp_live_");

        String listed = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/api-keys")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Nothing stores it, so nothing can return it. A key that could be read
        // back would make the database as sensitive as the keys themselves.
        assertThat(listed).doesNotContain(secret);
        assertThat(listed).doesNotContain("\"secret\"");
    }

    @Test
    @DisplayName("sends an unused key's lastUsedAt as an explicit null, not an absent field")
    void nullFieldsAreSerialisedRatherThanDropped() throws Exception {
        Tokens tokens = loginAs("key-nulls@example.com", RoleName.CITY_OPERATOR);
        create(tokens.accessToken(), """
                {"name":"Never used","scopes":["telemetry:read"]}
                """);

        JsonNode key = objectMapper.readTree(
                        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/api-keys")
                                        .header("Authorization", "Bearer " + tokens.accessToken()))
                                .andExpect(status().isOk())
                                .andReturn().getResponse().getContentAsString())
                .path("data").path(0);

        // The distinction this pins down is invisible from Java and decisive in
        // TypeScript: a client field typed `string | null` is `undefined` when
        // the key is missing, so every `x === null` guard written to catch the
        // absence falls straight through it. This one reached the interface as
        // "last used NaNd ago" on a key nobody had used.
        assertThat(key.has("lastUsedAt")).isTrue();
        assertThat(key.get("lastUsedAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("refuses to mint a key with permissions the creator lacks")
    void refusesPrivilegeEscalation() throws Exception {
        Tokens tokens = loginAs("key-escalate@example.com", RoleName.CITY_OPERATOR);

        // Without this check anyone able to create a key could mint one with
        // system:manage and use it — the endpoint would bypass the whole
        // permission model.
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/api-keys")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Escalation","scopes":["system:manage"]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the key authenticates a request, with only its own scopes")
    void keyAuthenticatesWithItsScopes() throws Exception {
        Tokens tokens = loginAs("key-auth@example.com", RoleName.CITY_OPERATOR);

        String secret = create(tokens.accessToken(), """
                {"name":"Read-only job","scopes":["telemetry:read"]}
                """).path("secret").asText();

        // It works for what it was granted.
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/live/by-slug/bengaluru")
                        .header("X-API-Key", secret))
                .andExpect(status().isOk());

        // And not for anything else, even though its owner holds more. Scopes
        // are the key's authority, not the owner's current permissions.
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/alerts/summary")
                        .header("X-API-Key", secret))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a revoked key stops working immediately")
    void revokedKeyIsRejected() throws Exception {
        Tokens tokens = loginAs("key-revoke@example.com", RoleName.CITY_OPERATOR);

        JsonNode created = create(tokens.accessToken(), """
                {"name":"Temporary","scopes":["telemetry:read"]}
                """);
        String secret = created.path("secret").asText();
        String id = created.path("key").path("id").asText();

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/live/by-slug/bengaluru")
                        .header("X-API-Key", secret))
                .andExpect(status().isOk());

        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/api-keys/" + id)
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Rotated\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/live/by-slug/bengaluru")
                        .header("X-API-Key", secret))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an unknown key is rejected without saying it is unknown")
    void unknownKeyIsRejected() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/live/by-slug/bengaluru")
                        .header("X-API-Key", "cp_live_completelymadeupvalue"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("cannot revoke someone else's key, and is not told it exists")
    void cannotRevokeAnotherUsersKey() throws Exception {
        Tokens owner = loginAs("key-owner@example.com", RoleName.CITY_OPERATOR);
        String id = create(owner.accessToken(), """
                {"name":"Theirs","scopes":["telemetry:read"]}
                """).path("key").path("id").asText();

        Tokens other = loginAs("key-stranger@example.com", RoleName.CITY_OPERATOR);

        // 404, not 403. Confirming that an id exists but belongs to someone else
        // is itself a disclosure.
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/api-keys/" + id)
                        .header("Authorization", "Bearer " + other.accessToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("only offers scopes the caller actually holds")
    void scopeCatalogueIsTheCallersOwn() throws Exception {
        Tokens tokens = loginAs("key-scopes@example.com", RoleName.CITY_OPERATOR);

        String body = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/api-keys/scopes")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode grantable = objectMapper.readTree(body).path("data").path("grantable");
        assertThat(grantable).isNotEmpty();
        // Offering a scope the caller cannot grant would put a control in the UI
        // whose only possible outcome is an error.
        for (JsonNode scope : grantable) {
            assertThat(scope.asText()).doesNotContain("system:manage");
        }
    }
}
