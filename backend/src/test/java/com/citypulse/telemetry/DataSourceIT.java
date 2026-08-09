package com.citypulse.telemetry;

import com.citypulse.support.IntegrationTest;
import com.citypulse.user.domain.RoleName;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The data sources view, and the one distinction it exists to make.
 *
 * <p>A source row says ACTIVE and carries a last_ingested_at, and neither is
 * evidence of anything: the status is a configuration, and the timestamp is
 * written by whatever writes the events — a retry can touch it without a single
 * row arriving. So the volume is counted from the event tables, and a source
 * claiming to run while delivering nothing is flagged rather than listed beside
 * the healthy ones.
 */
@DisplayName("Data sources")
class DataSourceIT extends IntegrationTest {

    @Test
    @DisplayName("lists the seeded feeds and labels them synthetic")
    void listsSeededSources() throws Exception {
        Tokens tokens = loginAs("sources-list@example.com", RoleName.CITY_OPERATOR);

        JsonNode body = objectMapper.readTree(mockMvc.perform(get("/api/v1/data-sources")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        JsonNode sources = body.path("data").path("sources");
        assertThat(sources).isNotEmpty();

        // Each source says what it is, and the two facts travel together.
        //
        // This assertion used to be "everything is SYNTHETIC", which was true
        // until V16 added CPCB. The rule it was really protecting is stronger
        // and still holds: a generated feed is labelled generated, and a real
        // one is labelled real. Getting that pairing wrong in either direction
        // is how synthetic data starts being read as measurement.
        for (JsonNode source : sources) {
            String mode = source.path("ingestionMode").asText();
            boolean demo = source.path("demoData").asBoolean();
            if ("SYNTHETIC".equals(mode)) {
                assertThat(demo)
                        .as("a synthetic feed must be flagged as demo data")
                        .isTrue();
            } else {
                assertThat(demo)
                        .as("a real feed (%s) must not be flagged as demo data", mode)
                        .isFalse();
            }
        }

        // And at least one of each exists, so this test cannot pass vacuously
        // if a future change removes one side.
        assertThat(sources).anySatisfy(s ->
                assertThat(s.path("ingestionMode").asText()).isEqualTo("SYNTHETIC"));
        assertThat(sources).anySatisfy(s ->
                assertThat(s.path("ingestionMode").asText()).isEqualTo("REST_API"));
    }

    @Test
    @DisplayName("flags an active source that has delivered nothing")
    void flagsSilentSource() throws Exception {
        Tokens tokens = loginAs("sources-silent@example.com", RoleName.CITY_OPERATOR);

        // The test database has no recent events, so every ACTIVE source is
        // silent — which is exactly the state that must not read as healthy.
        JsonNode data = objectMapper.readTree(mockMvc.perform(get("/api/v1/data-sources")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");

        assertThat(data.path("silent").asInt()).isGreaterThan(0);
        assertThat(data.path("active").asInt()).isGreaterThan(0);

        for (JsonNode source : data.path("sources")) {
            if ("ACTIVE".equals(source.path("status").asText())) {
                assertThat(source.path("rowsInWindow").asLong()).isZero();
                assertThat(source.path("silent").asBoolean()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("never exposes the config column")
    void doesNotExposeConfig() throws Exception {
        Tokens tokens = loginAs("sources-config@example.com", RoleName.CITY_OPERATOR);

        // config holds shaping parameters that are non-secret by convention
        // rather than by guarantee. Nothing renders it, so nothing returns it.
        String body = mockMvc.perform(get("/api/v1/data-sources")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("\"config\"");
    }

    @Test
    @DisplayName("gives every feed one of the three provenances, and pairs it with demoData")
    void labelsProvenance() throws Exception {
        Tokens tokens = loginAs("sources-provenance@example.com", RoleName.CITY_OPERATOR);

        JsonNode sources = objectMapper.readTree(mockMvc.perform(get("/api/v1/data-sources")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .path("data").path("sources");

        // demoData asks only whether this platform invented the row, so the two
        // real provenances answer it identically. The pairing that must hold is
        // the other direction: nothing generated may claim to be measured or
        // modelled, and nothing real may be flagged as demo data.
        for (JsonNode source : sources) {
            String provenance = source.path("provenance").asText();
            assertThat(provenance)
                    .as("%s must declare a provenance", source.path("code").asText())
                    .isIn("MEASURED", "MODELLED", "SYNTHETIC");
            assertThat(source.path("demoData").asBoolean())
                    .as("%s is %s, so demoData must be %s",
                            source.path("code").asText(), provenance, "SYNTHETIC".equals(provenance))
                    .isEqualTo("SYNTHETIC".equals(provenance));
        }

        // All three exist, so this cannot pass by having only one kind of feed.
        assertThat(sources).anySatisfy(s ->
                assertThat(s.path("provenance").asText()).isEqualTo("SYNTHETIC"));
        assertThat(sources).anySatisfy(s ->
                assertThat(s.path("provenance").asText()).isEqualTo("MEASURED"));
        assertThat(sources).anySatisfy(s ->
                assertThat(s.path("provenance").asText()).isEqualTo("MODELLED"));
    }

    @Test
    @DisplayName("carries the credits the real feeds' licences require")
    void carriesAttribution() throws Exception {
        Tokens tokens = loginAs("sources-attribution@example.com", RoleName.CITY_OPERATOR);

        JsonNode sources = objectMapper.readTree(mockMvc.perform(get("/api/v1/data-sources")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .path("data").path("sources");

        for (JsonNode source : sources) {
            JsonNode attribution = source.path("attribution");
            assertThat(attribution.isArray())
                    .as("%s must carry an attribution list, empty if it owes none",
                            source.path("code").asText())
                    .isTrue();

            if ("SYNTHETIC".equals(source.path("provenance").asText())) {
                // This platform's own output credits nobody.
                assertThat(attribution).isEmpty();
            } else {
                // WAQI's terms make attribution mandatory and Open-Meteo's data
                // is CC BY 4.0, so a real feed reaching the client with no
                // credit would put the deployment outside the terms it accepted
                // by fetching the data. A name is required; a URL is not, since
                // some agencies are named in a response without one.
                assertThat(attribution).isNotEmpty();
                for (JsonNode credit : attribution) {
                    assertThat(credit.path("name").asText()).isNotBlank();
                }
            }
        }
    }

    @Test
    @DisplayName("requires authentication")
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/data-sources")).andExpect(status().isUnauthorized());
    }
    @Test
    @DisplayName("reports pipeline quality, and only the stages that are instrumented")
    void reportsPipelineQuality() throws Exception {
        Tokens tokens = loginAs("health-stages@example.com", RoleName.CITY_OPERATOR);

        JsonNode data = objectMapper.readTree(mockMvc.perform(get("/api/v1/data-sources/health")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");

        assertThat(data.path("windowHours").asInt()).isPositive();
        assertThat(data.path("stages").isArray()).isTrue();

        // A stage absent from this list is uninstrumented, not idle. Filling it
        // in with zeroes would report a stage nobody measures as a stage that
        // handled nothing — a clean bill of health nobody issued.
        for (JsonNode stage : data.path("stages")) {
            assertThat(stage.path("stage").asText()).isNotBlank();
            assertThat(stage.path("recordsReceived").asLong()).isNotNegative();
        }
    }

    @Test
    @DisplayName("leaves the validity ratio null when nothing arrived")
    void nullRatioForAnEmptyWindow() throws Exception {
        Tokens tokens = loginAs("health-empty@example.com", RoleName.CITY_OPERATOR);

        // The test database has no quality metrics, so there are no stages at
        // all — which must not be rendered as a pipeline that received nothing
        // and validated none of it.
        JsonNode data = objectMapper.readTree(mockMvc.perform(get("/api/v1/data-sources/health")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");

        for (JsonNode stage : data.path("stages")) {
            if (stage.path("recordsReceived").asLong() == 0) {
                // A ratio over an empty denominator is undefined. Zero would say
                // "nothing was valid", which is a different and alarming claim.
                assertThat(notMeasured(stage, "validityRatio")).isTrue();
            }
        }
    }

    @Test
    @DisplayName("health requires authentication too")
    void healthRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/data-sources/health")).andExpect(status().isUnauthorized());
    }

}
