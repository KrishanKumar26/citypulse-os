package com.citypulse.intervention;

import com.citypulse.support.IntegrationTest;
import com.citypulse.user.domain.RoleName;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Interventions and the measurement that follows them (PRD §16).
 *
 * <p>This is the easiest place in the product to manufacture a success story, so
 * the tests are mostly about what the measurement refuses to say.
 */
@DisplayName("Interventions")
class InterventionIT extends IntegrationTest {

    private String recordBody(String zoneUid, Instant startedAt, Instant endedAt) {
        return """
                {"title":"Rerouted eastern corridor",
                 "actionType":"TRAFFIC_DIVERSION",
                 "citySlug":"bengaluru",
                 %s
                 "startedAt":"%s"%s,
                 "comparisonMinutes":60}
                """.formatted(
                zoneUid == null ? "" : "\"zoneId\":\"" + zoneUid + "\",",
                startedAt,
                endedAt == null ? "" : ",\"endedAt\":\"" + endedAt + "\"");
    }

    private JsonNode create(String token, String body) throws Exception {
        return objectMapper.readTree(mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/interventions")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("data");
    }

    private String zoneUid(String code) {
        return transactionTemplate.execute(status -> (String) entityManager
                .createNativeQuery("SELECT uid::text FROM zones WHERE code = :code")
                .setParameter("code", code)
                .getSingleResult());
    }

    @Test
    @DisplayName("attributes the action to whoever recorded it")
    void attributesToRecorder() throws Exception {
        Tokens tokens = loginAs("intervention-author@example.com", RoleName.CITY_OPERATOR);

        JsonNode created = create(tokens.accessToken(),
                recordBody(zoneUid("BLR-WHF"), Instant.now().minus(2, ChronoUnit.HOURS), null));

        // An intervention with no author is an assertion nobody owns, and every
        // impact figure later read from it rests on someone having been there.
        assertThat(created.path("recordedBy").asText()).isEqualTo("intervention-author@example.com");
        assertThat(created.path("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("reports unmeasurable rather than no effect when a side has no windows")
    void unmeasurableWithoutWindows() throws Exception {
        Tokens tokens = loginAs("intervention-empty@example.com", RoleName.CITY_OPERATOR);

        // No curated telemetry exists in this test, so neither side has data.
        JsonNode created = create(tokens.accessToken(),
                recordBody(zoneUid("BLR-WHF"), Instant.now().minus(2, ChronoUnit.HOURS), null));

        JsonNode impact = created.path("impact");
        // The distinction the whole feature turns on: an action taken during a
        // feed outage must not score however the missing data averaged.
        assertThat(impact.path("measurable").asBoolean()).isFalse();
        assertThat(impact.path("unmeasurableReason").asText()).isNotBlank();
        assertThat(impact.path("metrics")).isEmpty();
    }

    @Test
    @DisplayName("measures nothing for a city-wide action, and says why")
    void cityWideHasNoZoneBaseline() throws Exception {
        Tokens tokens = loginAs("intervention-city@example.com", RoleName.CITY_OPERATOR);

        JsonNode created = create(tokens.accessToken(),
                recordBody(null, Instant.now().minus(90, ChronoUnit.MINUTES), null));

        // There is no city-level baseline. Inventing one would measure the
        // action against a normal that was never learned.
        assertThat(notMeasured(created, "impact")).isTrue();
        assertThat(notMeasured(created, "zoneId")).isTrue();
    }

    @Test
    @DisplayName("marks a running intervention provisional")
    void runningIsProvisional() throws Exception {
        Tokens tokens = loginAs("intervention-live@example.com", RoleName.CITY_OPERATOR);

        JsonNode created = create(tokens.accessToken(),
                recordBody(zoneUid("BLR-WHF"), Instant.now().minus(30, ChronoUnit.MINUTES), null));

        // The window after it is still filling, so the figure will move. Saying
        // so stops a provisional reading being quoted as a result.
        assertThat(created.path("impact").path("provisional").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("refuses a start in the future")
    void rejectsFutureStart() throws Exception {
        Tokens tokens = loginAs("intervention-future@example.com", RoleName.CITY_OPERATOR);

        // Rejected rather than clamped: quietly moving it to now would record a
        // time nobody chose.
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/interventions")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recordBody(zoneUid("BLR-WHF"), Instant.now().plus(2, ChronoUnit.HOURS), null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("refuses a zone from another city")
    void rejectsForeignZone() throws Exception {
        Tokens tokens = loginAs("intervention-foreign@example.com", RoleName.CITY_OPERATOR);

        // Accepting it would measure the action against another city's baseline.
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/interventions")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recordBody(zoneUid("MUM-BKC"), Instant.now().minus(1, ChronoUnit.HOURS), null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("requires authentication to read")
    void readRequiresAuth() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/interventions?citySlug=bengaluru"))
                .andExpect(status().isUnauthorized());
    }
}
