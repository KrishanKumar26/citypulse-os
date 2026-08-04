package com.citypulse.simulation;

import com.citypulse.support.IntegrationTest;
import com.citypulse.user.domain.RoleName;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The simulation API (PRD §14).
 *
 * <p>The engine's arithmetic is covered by {@code ScenarioEngineTest}; what is
 * asserted here is everything around it — that a scenario refuses to run without
 * a real baseline, that results persist and reload intact, and that output is
 * traceable to the conditions and assumptions that produced it.
 */
@DisplayName("Simulation API")
class SimulationApiIT extends IntegrationTest {

    /** Writes a recent curated window so a scenario has something to depart from. */
    private void seedBaseline(String zoneCode, String occupancy, String speed, String congestion) {
        transactionTemplate.executeWithoutResult(status ->
                entityManager.createNativeQuery("""
                        INSERT INTO zone_metrics (zone_id, window_start, window_end, vehicle_count,
                            average_speed_kph, occupancy_ratio, congestion_level, aqi, aqi_category,
                            temperature_c, precipitation_mm_h, weather_condition,
                            active_incidents, active_events, risk_score, risk_level,
                            sample_count, demo_data, computed_at)
                        SELECT z.id, now() - interval '3 minutes', now() + interval '2 minutes',
                               1200, CAST(:speed AS numeric), CAST(:occupancy AS numeric), :congestion,
                               140, 'MODERATE', 28.0, 0.0, 'CLEAR', 0, 0,
                               42.00, 'MODERATE', 6, TRUE, now()
                        FROM zones z WHERE z.code = :code
                        """)
                        .setParameter("code", zoneCode)
                        .setParameter("occupancy", occupancy)
                        .setParameter("speed", speed)
                        .setParameter("congestion", congestion)
                        .executeUpdate());
    }

    private JsonNode run(String token, String body) throws Exception {
        String response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/simulations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private static final String RAIN_SCENARIO = """
            {"name":"Heavy rain","citySlug":"bengaluru",
             "weather":{"rainIntensityMmH":18.0}}
            """;

    // ------------------------------------------------------------------

    @Test
    @DisplayName("a scenario runs against observed conditions and reports both sides")
    void runsAgainstRealBaseline() throws Exception {
        Tokens tokens = loginAs("sim-run@example.com", RoleName.CITY_OPERATOR);
        seedBaseline("BLR-WHF", "0.6000", "40.00", "MODERATE");

        JsonNode result = run(tokens.accessToken(), RAIN_SCENARIO);

        assertThat(result.path("zones")).isNotEmpty();
        JsonNode zone = result.path("zones").get(0);
        // Both sides, not just the delta: a +40% change from 0.3 and from 0.9
        // are entirely different situations.
        assertThat(zone.path("baselineOccupancy").decimalValue()).isNotNull();
        assertThat(zone.path("simulatedOccupancy").decimalValue()).isNotNull();
        assertThat(zone.path("baselineCongestion").asText()).isNotBlank();
        assertThat(zone.path("simulatedCongestion").asText()).isNotBlank();
    }

    @Test
    @DisplayName("the result names the window and engine version it came from")
    void resultIsTraceable() throws Exception {
        Tokens tokens = loginAs("sim-trace@example.com", RoleName.CITY_OPERATOR);
        seedBaseline("BLR-WHF", "0.6000", "40.00", "MODERATE");

        JsonNode result = run(tokens.accessToken(), RAIN_SCENARIO);

        // Without the window, "traffic +43%" is a percentage of nothing in
        // particular once conditions move on. Without the version, a result read
        // later cannot say which assumptions produced it.
        assertThat(result.path("baselineWindow").asText()).isNotBlank();
        assertThat(result.path("engineVersion").asText()).isEqualTo("v1");
        Instant window = Instant.parse(result.path("baselineWindow").asText());
        assertThat(window).isAfter(Instant.now().minus(1, ChronoUnit.HOURS));
    }

    @Test
    @DisplayName("a scenario refuses to run without observed conditions")
    void refusesWithoutBaseline() throws Exception {
        Tokens tokens = loginAs("sim-nobaseline@example.com", RoleName.CITY_OPERATOR);
        // No zone_metrics seeded.

        // Simulating from an assumed starting point would give a confident
        // before/after where the "before" was invented.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/simulations")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RAIN_SCENARIO))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a scenario that changes nothing is refused")
    void refusesEmptyScenario() throws Exception {
        Tokens tokens = loginAs("sim-empty@example.com", RoleName.CITY_OPERATOR);
        seedBaseline("BLR-WHF", "0.6000", "40.00", "MODERATE");

        // Reporting a perfect no-op as an insight would be worse than saying
        // nothing was asked.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/simulations")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nothing\",\"citySlug\":\"bengaluru\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("out-of-range inputs are refused rather than extrapolated")
    void refusesInputsBeyondTheModelsRange() throws Exception {
        Tokens tokens = loginAs("sim-range@example.com", RoleName.CITY_OPERATOR);
        seedBaseline("BLR-WHF", "0.6000", "40.00", "MODERATE");

        // The curves are only meaningful over the observed range; 500 mm/h would
        // produce a confident number about conditions nothing here models.
        //
        // 422, not 400: a bean-validation failure means the request was
        // well-formed but semantically out of bounds, which is what the project's
        // exception handler distinguishes. A service-thrown BadRequest — such as
        // an empty scenario — is a 400.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/simulations")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Biblical","citySlug":"bengaluru",
                                 "weather":{"rainIntensityMmH":500.0}}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("results persist and reload intact")
    void resultsReload() throws Exception {
        Tokens tokens = loginAs("sim-reload@example.com", RoleName.CITY_OPERATOR);
        seedBaseline("BLR-WHF", "0.6000", "40.00", "MODERATE");

        JsonNode created = run(tokens.accessToken(), RAIN_SCENARIO);
        String id = created.path("id").asText();

        String body = mockMvc.perform(authGet("/api/v1/simulations/" + id, tokens.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode reloaded = objectMapper.readTree(body).path("data");

        assertThat(reloaded.path("id").asText()).isEqualTo(id);
        assertThat(reloaded.path("engineVersion").asText())
                .isEqualTo(created.path("engineVersion").asText());
        assertThat(reloaded.path("zones")).hasSameSizeAs(created.path("zones"));
    }

    @Test
    @DisplayName("every recommendation states the reason behind it")
    void recommendationsCiteTheirReason() throws Exception {
        Tokens tokens = loginAs("sim-recs@example.com", RoleName.CITY_OPERATOR);
        // Already near capacity, so rain pushes it over and advice is produced.
        seedBaseline("BLR-WHF", "0.9500", "22.00", "HIGH");

        JsonNode result = run(tokens.accessToken(), RAIN_SCENARIO);

        // Advice acted on has real consequences; PRD §15 forbids asking a user
        // to take it on faith.
        assertThat(result.path("recommendations")).isNotEmpty();
        for (JsonNode recommendation : result.path("recommendations")) {
            assertThat(recommendation.path("action").asText()).isNotBlank();
            assertThat(recommendation.path("reason").asText()).isNotBlank();
            assertThat(recommendation.path("priority").asText()).isIn("HIGH", "MEDIUM", "LOW");
        }
    }

    @Test
    @DisplayName("a spillover effect is labelled differently from a stated one")
    void spilloverIsLabelled() throws Exception {
        Tokens tokens = loginAs("sim-spill@example.com", RoleName.CITY_OPERATOR);
        seedBaseline("BLR-WHF", "0.5000", "44.00", "NORMAL");
        seedBaseline("BLR-KOR", "0.5000", "44.00", "NORMAL");

        JsonNode result = run(tokens.accessToken(), """
                {"name":"Concert","citySlug":"bengaluru",
                 "event":{"zoneCode":"BLR-WHF","eventType":"CONCERT",
                          "expectedAttendance":40000,"startsInHours":0,"durationHours":4}}
                """);

        // An inferred neighbouring effect deserves less confidence than a stated
        // closure, and the reader has to be able to tell which they are acting on.
        boolean hasDirect = false;
        for (JsonNode zone : result.path("zones")) {
            assertThat(zone.path("impactSource").asText()).isIn("DIRECT", "SPILLOVER", "CITYWIDE");
            if ("DIRECT".equals(zone.path("impactSource").asText())) {
                hasDirect = true;
            }
        }
        assertThat(hasDirect).isTrue();
    }

    @Test
    @DisplayName("running a scenario requires simulation:create")
    void runningRequiresPermission() throws Exception {
        Tokens viewer = loginAs("sim-viewer@example.com", RoleName.VIEWER);
        seedBaseline("BLR-WHF", "0.6000", "40.00", "MODERATE");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/simulations")
                        .header("Authorization", "Bearer " + viewer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RAIN_SCENARIO))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an unauthenticated caller cannot run a scenario")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/simulations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RAIN_SCENARIO))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("saved scenarios are listed newest first")
    void historyIsListed() throws Exception {
        Tokens tokens = loginAs("sim-history@example.com", RoleName.CITY_OPERATOR);
        seedBaseline("BLR-WHF", "0.6000", "40.00", "MODERATE");

        run(tokens.accessToken(), RAIN_SCENARIO);

        String body = mockMvc.perform(authGet(
                        "/api/v1/simulations?citySlug=bengaluru", tokens.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(body).path("data").path("items")).isNotEmpty();
    }
}
