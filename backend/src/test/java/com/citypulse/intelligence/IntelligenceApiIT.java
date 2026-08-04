package com.citypulse.intelligence;

import com.citypulse.support.IntegrationTest;
import com.citypulse.user.domain.RoleName;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The intelligence API (PRD §12, §13, §16).
 *
 * <p>What is asserted is that the platform declines to overstate. An anomaly
 * must carry the baseline it departed from; a correlation must carry its counts
 * and disclaim causation; a memory recall with too few examples must say so
 * rather than return a median over three.
 */
@DisplayName("Intelligence API")
class IntelligenceApiIT extends IntegrationTest {

    private void seedAnomaly(String zoneCode, String metric, String observed,
                             String baseline, String severity) {
        transactionTemplate.executeWithoutResult(status ->
                entityManager.createNativeQuery("""
                        INSERT INTO anomalies (uid, zone_id, city_id, metric, anomaly_type,
                            severity, window_start, observed_value, baseline_value, baseline_mad,
                            deviation_score, percent_change, baseline_samples, explanation, demo_data)
                        SELECT gen_random_uuid(), z.id, z.city_id, :metric, 'SPIKE', :severity,
                               now() - interval '10 minutes',
                               CAST(:observed AS numeric), CAST(:baseline AS numeric), 400.0,
                               12.5, 122.5, 54,
                               'Vehicle volume of 17,800.00 is 12.5 standard deviations above the '
                               || 'normal 8,000.00 for this zone at this hour. Baseline learned '
                               || 'from 54 historical windows.',
                               TRUE
                        FROM zones z WHERE z.code = :code
                        """)
                        .setParameter("code", zoneCode)
                        .setParameter("metric", metric)
                        .setParameter("severity", severity)
                        .setParameter("observed", observed)
                        .setParameter("baseline", baseline)
                        .executeUpdate());
    }

    private void seedCorrelation(String a, String b, String lift, int both, int total) {
        transactionTemplate.executeWithoutResult(status ->
                entityManager.createNativeQuery("""
                        INSERT INTO condition_correlations (city_id, condition_a, condition_b,
                            lift, support, confidence, windows_with_a, windows_with_both,
                            windows_total, computed_from, computed_to)
                        SELECT c.id, :a, :b, CAST(:lift AS numeric), 0.05, 0.30,
                               CAST(:both AS integer) * 3, CAST(:both AS integer),
                               CAST(:total AS integer),
                               now() - interval '28 days', now()
                        FROM cities c WHERE c.slug = 'bengaluru'
                        """)
                        .setParameter("a", a).setParameter("b", b).setParameter("lift", lift)
                        .setParameter("both", String.valueOf(both))
                        .setParameter("total", String.valueOf(total))
                        .executeUpdate());
    }

    /** Writes n comparable situations sharing one fingerprint. */
    private void seedSituations(String zoneCode, int count, String rainBand, String hourBand) {
        transactionTemplate.executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                entityManager.createNativeQuery("""
                        INSERT INTO situation_memory (uid, zone_id, city_id, occurred_at,
                            rain_band, day_type, hour_band, had_event, incident_band, congestion_band,
                            occupancy_at_start, speed_at_start, risk_at_start,
                            outcome_horizon_minutes, peak_occupancy, min_speed_kph, peak_risk,
                            occupancy_change_pct, speed_change_pct, risk_change_pct, demo_data)
                        SELECT gen_random_uuid(), z.id, z.city_id,
                               now() - (CAST(:offset AS integer) || ' days')::interval,
                               :rain, 'WEEKDAY', :hour, FALSE, 'NONE', 'MODERATE',
                               0.6000, 38.00, 42.00, 120, 0.7200, 30.00, 55.00,
                               20.00, -21.00, 31.00, TRUE
                        FROM zones z WHERE z.code = :code
                        """)
                        .setParameter("code", zoneCode)
                        .setParameter("rain", rainBand)
                        .setParameter("hour", hourBand)
                        .setParameter("offset", String.valueOf(i + 1))
                        .executeUpdate();
            }
        });
    }

    private JsonNode getData(String path, String token) throws Exception {
        String body = mockMvc.perform(authGet(path, token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("an anomaly carries the baseline it departed from")
    void anomalyCitesItsBaseline() throws Exception {
        Tokens tokens = loginAs("intel-anomaly@example.com", RoleName.CITY_OPERATOR);
        seedAnomaly("BLR-WHF", "vehicle_count", "17800", "8000", "CRITICAL");

        JsonNode item = getData("/api/v1/anomalies?citySlug=bengaluru", tokens.accessToken())
                .path("items").get(0);

        // PRD §13's form: the observation, the normal, and the gap. An anomaly
        // that cannot be stated that way is one the user must take on faith.
        assertThat(item.path("observedValue").decimalValue()).isEqualByComparingTo("17800");
        assertThat(item.path("baselineValue").decimalValue()).isEqualByComparingTo("8000");
        assertThat(item.path("baselineSamples").asInt()).isEqualTo(54);
        assertThat(item.path("explanation").asText()).contains("17,800").contains("8,000");
    }

    @Test
    @DisplayName("a correlation carries its counts and disclaims causation")
    void correlationDisclaimsCausation() throws Exception {
        Tokens tokens = loginAs("intel-corr@example.com", RoleName.ANALYST);
        seedCorrelation("RAIN", "HIGH_CONGESTION", "2.4000", 500, 60000);

        JsonNode item = getData("/api/v1/anomalies/correlations?citySlug=bengaluru",
                tokens.accessToken()).get(0);

        // Rain and congestion rise together partly because both are heavier at
        // the same times of day, and nothing here separates that from rain
        // making traffic worse. The payload says so rather than leaving a client
        // free to present co-occurrence as cause.
        assertThat(item.path("impliesCausation").asBoolean()).isFalse();
        assertThat(item.path("windowsWithBoth").asInt()).isEqualTo(500);
        assertThat(item.path("windowsTotal").asInt()).isEqualTo(60000);
        assertThat(item.path("statement").asText()).contains("coincides with");
    }

    @Test
    @DisplayName("City Memory reports outcomes when it has enough examples")
    void memoryReportsRealOutcomes() throws Exception {
        Tokens tokens = loginAs("intel-memory@example.com", RoleName.ANALYST);
        seedSituations("BLR-WHF", 8, "MODERATE", "EVENING_PEAK");

        JsonNode recall = getData(
                "/api/v1/anomalies/memory?citySlug=bengaluru&rainBand=MODERATE"
                + "&hourBand=EVENING_PEAK&dayType=WEEKDAY&hadEvent=false&incidentBand=NONE",
                tokens.accessToken());

        assertThat(recall.path("sufficientData").asBoolean()).isTrue();
        assertThat(recall.path("matchCount").asInt()).isEqualTo(8);
        // Measured from what actually followed, not predicted.
        assertThat(recall.path("medianOccupancyChangePct").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(recall.path("examples")).isNotEmpty();
    }

    @Test
    @DisplayName("City Memory says so rather than guessing from too few examples")
    void memoryDeclinesOnThinHistory() throws Exception {
        Tokens tokens = loginAs("intel-thin@example.com", RoleName.ANALYST);
        seedSituations("BLR-WHF", 2, "HEAVY", "OVERNIGHT");

        JsonNode recall = getData(
                "/api/v1/anomalies/memory?citySlug=bengaluru&rainBand=HEAVY"
                + "&hourBand=OVERNIGHT&dayType=WEEKDAY&hadEvent=false&incidentBand=NONE",
                tokens.accessToken());

        // A median over two examples is not a finding. PRD §15 requires the
        // platform to say it cannot tell rather than produce a number.
        assertThat(recall.path("sufficientData").asBoolean()).isFalse();
        assertThat(recall.path("medianOccupancyChangePct").isNull()
                || !recall.hasNonNull("medianOccupancyChangePct")).isTrue();
        assertThat(recall.path("summary").asText()).contains("fewer than");
    }

    @Test
    @DisplayName("an unseen combination returns zero matches, not an error")
    void unseenCombinationIsNotAnError() throws Exception {
        Tokens tokens = loginAs("intel-unseen@example.com", RoleName.ANALYST);

        JsonNode recall = getData(
                "/api/v1/anomalies/memory?citySlug=bengaluru&rainBand=HEAVY"
                + "&hourBand=OVERNIGHT&dayType=WEEKEND&hadEvent=true&incidentBand=MANY",
                tokens.accessToken());

        // "We have not seen this before" is a real answer, not a failure.
        assertThat(recall.path("matchCount").asInt()).isZero();
        assertThat(recall.path("sufficientData").asBoolean()).isFalse();
        assertThat(recall.path("summary").asText()).isNotBlank();
    }

    @Test
    @DisplayName("the insights summary gathers anomalies, correlations and memory")
    void insightsSummaryIsAssembled() throws Exception {
        Tokens tokens = loginAs("intel-summary@example.com", RoleName.ANALYST);
        seedAnomaly("BLR-WHF", "vehicle_count", "17800", "8000", "HIGH");
        seedCorrelation("INCIDENT_OPEN", "SLOW_TRAFFIC", "2.7000", 2408, 65248);

        JsonNode summary = getData("/api/v1/anomalies/insights?citySlug=bengaluru",
                tokens.accessToken());

        assertThat(summary.path("anomaliesLast24h").asInt()).isPositive();
        assertThat(summary.path("topAnomalies")).isNotEmpty();
        assertThat(summary.path("correlations")).isNotEmpty();
    }

    @Test
    @DisplayName("reading anomalies requires anomaly:read")
    void anomaliesRequirePermission() throws Exception {
        mockMvc.perform(get("/api/v1/anomalies?citySlug=bengaluru"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an unknown city is a 404, not an empty result")
    void unknownCityIsNotFound() throws Exception {
        Tokens tokens = loginAs("intel-404@example.com", RoleName.ANALYST);

        // An empty result for a typo'd slug would look like a city with nothing
        // unusual happening.
        mockMvc.perform(authGet("/api/v1/anomalies?citySlug=atlantis", tokens.accessToken()))
                .andExpect(status().isNotFound());
    }
}
