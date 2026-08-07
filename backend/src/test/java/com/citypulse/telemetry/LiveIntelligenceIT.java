package com.citypulse.telemetry;

import com.citypulse.support.IntegrationTest;
import com.citypulse.user.domain.RoleName;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The live conditions API (PRD §9).
 *
 * <p>The phase's exit criterion is that every displayed figure traces back to a
 * warehouse row, so most of what is asserted here is provenance rather than
 * values: that a reported zone names the window it came from, that a silent zone
 * reports null instead of zero, and that a stale pipeline is visible as staleness
 * rather than as a calm city.
 */
@DisplayName("Live intelligence API")
class LiveIntelligenceIT extends IntegrationTest {

    private static final String BENGALURU = "bengaluru";

    /**
     * Writes a curated window exactly as the pipeline does.
     *
     * <p>Native SQL because {@code ZoneMetric} is deliberately read-only from the
     * application's side — the data platform owns those rows, and giving the
     * entity setters so a test could write one would undo that.
     */
    void insertWindow(String zoneCode, Instant windowStart, String occupancy, String speed,
                      String congestion, Integer aqi, String riskScore, String riskLevel,
                      int incidents, int samples) {
        // transactionTemplate, not @Transactional: this is called from another
        // method of the same class, so the annotation would be bypassed by
        // Spring's proxy and the native update would fail with
        // TransactionRequiredException.
        transactionTemplate.executeWithoutResult(status ->
                entityManager.createNativeQuery("""
                INSERT INTO zone_metrics (zone_id, window_start, window_end, vehicle_count,
                    average_speed_kph, occupancy_ratio, congestion_level, aqi, aqi_category,
                    temperature_c, precipitation_mm_h, weather_condition,
                    active_incidents, active_events, risk_score, risk_level,
                    sample_count, demo_data, computed_at)
                SELECT z.id, CAST(:start AS timestamptz), CAST(:start AS timestamptz) + interval '5 minutes',
                       1200, CAST(:speed AS numeric), CAST(:occupancy AS numeric), :congestion,
                       CAST(:aqi AS integer), 'MODERATE', 28.5, 0.0, 'CLEAR',
                       CAST(:incidents AS smallint), 0,
                       CAST(:risk AS numeric), :riskLevel, CAST(:samples AS integer), TRUE, now()
                FROM zones z WHERE z.code = :code
                """)
                .setParameter("code", zoneCode)
                .setParameter("start", windowStart.toString())
                .setParameter("occupancy", occupancy)
                .setParameter("speed", speed)
                .setParameter("congestion", congestion)
                .setParameter("aqi", aqi == null ? null : aqi.toString())
                .setParameter("risk", riskScore)
                .setParameter("riskLevel", riskLevel)
                .setParameter("incidents", String.valueOf(incidents))
                .setParameter("samples", String.valueOf(samples))
                .executeUpdate());
    }

    /**
     * True when a field is absent or explicitly null.
     *
     * <p>Jackson omits null fields from the response, so an unmeasured metric
     * arrives as a missing key rather than a JSON null — and {@code isNull()}
     * reports false for a missing node. Either shape means "not measured", which
     * is what the assertion is actually about.
     */
    private boolean notMeasured(JsonNode zone, String field) {
        return !zone.hasNonNull(field);
    }

    private JsonNode snapshot(String accessToken) throws Exception {
        String body = mockMvc.perform(authGet("/api/v1/live/by-slug/" + BENGALURU, accessToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    private JsonNode zone(JsonNode snapshot, String code) {
        for (JsonNode z : snapshot.path("zones")) {
            if (code.equals(z.path("zoneCode").asText())) {
                return z;
            }
        }
        throw new AssertionError("zone " + code + " missing from the snapshot");
    }

    // ------------------------------------------------------------------
    // Provenance
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a reporting zone names the curated window its numbers came from")
    void reportingZoneCitesItsWindow() throws Exception {
        Tokens tokens = loginAs("live-provenance@example.com", RoleName.CITY_OPERATOR);
        Instant window = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(2, ChronoUnit.MINUTES);
        insertWindow("BLR-WHF", window, "0.62", "42.10", "MODERATE", 140, "38.40", "MODERATE", 0, 6);

        JsonNode zone = zone(snapshot(tokens.accessToken()), "BLR-WHF");

        assertThat(zone.path("hasData").asBoolean()).isTrue();
        assertThat(zone.path("windowStart").asText()).isNotBlank();
        assertThat(zone.path("sampleCount").asInt()).isEqualTo(6);
        assertThat(zone.path("occupancyRatio").decimalValue()).isEqualByComparingTo("0.6200");
        assertThat(zone.path("congestionLevel").asText()).isEqualTo("MODERATE");
    }

    @Test
    @DisplayName("a zone with no telemetry reports null, not zero")
    void silentZoneReportsNullNotZero() throws Exception {
        Tokens tokens = loginAs("live-silent@example.com", RoleName.CITY_OPERATOR);
        // Nothing inserted at all.

        JsonNode zone = zone(snapshot(tokens.accessToken()), "BLR-WHF");

        assertThat(zone.path("hasData").asBoolean()).isFalse();
        // The distinction matters: zero congestion is a measurement, no reading is
        // an absence, and a dashboard that renders both as "0" reports a feed
        // outage as a quiet street.
        assertThat(notMeasured(zone, "occupancyRatio")).isTrue();
        assertThat(notMeasured(zone, "averageSpeedKph")).isTrue();
        assertThat(notMeasured(zone, "riskScore")).isTrue();
        assertThat(notMeasured(zone, "windowStart")).isTrue();
    }

    @Test
    @DisplayName("a monitored zone stays in the response even when it is silent")
    void silentZoneIsStillListed() throws Exception {
        Tokens tokens = loginAs("live-listed@example.com", RoleName.CITY_OPERATOR);
        insertWindow("BLR-WHF", Instant.now().minus(2, ChronoUnit.MINUTES),
                "0.40", "45.00", "NORMAL", 90, "20.00", "NORMAL", 0, 6);

        JsonNode snapshot = snapshot(tokens.accessToken());

        // Dropping silent zones would make the map shrink as feeds failed, which
        // hides the outage instead of showing it.
        assertThat(snapshot.path("zones")).hasSizeGreaterThan(1);
        assertThat(snapshot.path("kpis").path("zonesMonitored").asInt())
                .isGreaterThan(snapshot.path("kpis").path("zonesReporting").asInt());
    }

    @Test
    @DisplayName("synthetic telemetry stays labelled all the way to the client")
    void demoDataIsLabelled() throws Exception {
        Tokens tokens = loginAs("live-demo@example.com", RoleName.CITY_OPERATOR);
        insertWindow("BLR-WHF", Instant.now().minus(1, ChronoUnit.MINUTES),
                "0.50", "44.00", "NORMAL", 100, "25.00", "NORMAL", 0, 6);

        // PRD §42: synthetic data must never be presented as real.
        assertThat(zone(snapshot(tokens.accessToken()), "BLR-WHF").path("demoData").asBoolean())
                .isTrue();
    }

    // ------------------------------------------------------------------
    // Freshness
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a snapshot with no data at all reports itself as stale")
    void emptySnapshotIsStale() throws Exception {
        Tokens tokens = loginAs("live-empty@example.com", RoleName.CITY_OPERATOR);

        JsonNode snapshot = snapshot(tokens.accessToken());

        // A dashboard that has never received anything must not present itself as
        // current — that is the difference between "no incidents" and "no idea".
        assertThat(snapshot.path("stale").asBoolean()).isTrue();
        assertThat(notMeasured(snapshot, "asOf")).isTrue();
        assertThat(snapshot.path("kpis").path("zonesReporting").asInt()).isZero();

        // The distinction the comment above claims, actually asserted. It was
        // not, and the counts came back as 0 — so a deployment whose every feed
        // had stopped rendered "0 active incidents" and read as a calm evening.
        // Summed from the reporting zones, an empty sum is not a measurement.
        JsonNode kpis = snapshot.path("kpis");
        assertThat(notMeasured(kpis, "activeIncidents")).isTrue();
        assertThat(notMeasured(kpis, "activeEvents")).isTrue();

        // Open alerts are counted from the alerts table, which needs no recent
        // window, so zero there is a genuine measurement and stays a number.
        assertThat(kpis.hasNonNull("activeAlerts")).isTrue();
        assertThat(kpis.path("activeAlerts").asInt()).isZero();
    }

    @Test
    @DisplayName("a window older than the currency bound is not reported as current")
    void agedWindowIsNotCurrent() throws Exception {
        Tokens tokens = loginAs("live-aged@example.com", RoleName.CITY_OPERATOR);
        // max-age is PT2H in application.yml; three hours is comfortably outside.
        insertWindow("BLR-WHF", Instant.now().minus(3, ChronoUnit.HOURS),
                "1.50", "9.00", "CRITICAL", 380, "92.00", "CRITICAL", 5, 6);

        JsonNode snapshot = snapshot(tokens.accessToken());

        // The severe numbers above must not surface: they describe three hours
        // ago, and showing them as "now" would be the worst kind of wrong.
        assertThat(zone(snapshot, "BLR-WHF").path("hasData").asBoolean()).isFalse();
        assertThat(snapshot.path("stale").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------
    // Aggregation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("city averages ignore silent zones rather than counting them as zero")
    void averagesExcludeSilentZones() throws Exception {
        Tokens tokens = loginAs("live-avg@example.com", RoleName.CITY_OPERATOR);
        Instant now = Instant.now().minus(1, ChronoUnit.MINUTES);
        insertWindow("BLR-WHF", now, "0.80", "40.00", "MODERATE", 200, "50.00", "MODERATE", 0, 6);
        insertWindow("BLR-KOR", now, "0.60", "44.00", "MODERATE", 100, "30.00", "MODERATE", 0, 6);

        JsonNode kpis = snapshot(tokens.accessToken()).path("kpis");

        // Mean of the two reporting zones, not of all eight. Counting silence as
        // zero would make the city look calmer exactly as visibility was lost.
        assertThat(kpis.path("zonesReporting").asInt()).isEqualTo(2);
        assertThat(kpis.path("averageCongestion").decimalValue()).isEqualByComparingTo("0.7000");
        assertThat(kpis.path("averageRiskScore").decimalValue()).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("degraded zones are counted from the stored risk band")
    void countsDegradedZones() throws Exception {
        Tokens tokens = loginAs("live-degraded@example.com", RoleName.CITY_OPERATOR);
        Instant now = Instant.now().minus(1, ChronoUnit.MINUTES);
        insertWindow("BLR-WHF", now, "1.20", "12.00", "CRITICAL", 320, "85.00", "CRITICAL", 3, 6);
        insertWindow("BLR-KOR", now, "0.90", "30.00", "HIGH", 220, "60.00", "HIGH", 1, 6);
        insertWindow("BLR-IND", now, "0.30", "46.00", "NORMAL", 80, "15.00", "NORMAL", 0, 6);

        assertThat(snapshot(tokens.accessToken()).path("kpis").path("zonesDegraded").asInt())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("only the newest window for a zone is reported")
    void reportsOnlyTheNewestWindow() throws Exception {
        Tokens tokens = loginAs("live-newest@example.com", RoleName.CITY_OPERATOR);
        Instant base = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        insertWindow("BLR-WHF", base.minus(30, ChronoUnit.MINUTES),
                "1.40", "10.00", "CRITICAL", 350, "90.00", "CRITICAL", 4, 6);
        insertWindow("BLR-WHF", base.minus(2, ChronoUnit.MINUTES),
                "0.35", "46.00", "NORMAL", 70, "12.00", "NORMAL", 0, 6);

        JsonNode zone = zone(snapshot(tokens.accessToken()), "BLR-WHF");

        assertThat(zone.path("congestionLevel").asText()).isEqualTo("NORMAL");
        assertThat(zone.path("occupancyRatio").decimalValue()).isEqualByComparingTo("0.3500");
    }

    // ------------------------------------------------------------------
    // Authorisation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unauthenticated caller cannot read live conditions")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/live/by-slug/" + BENGALURU))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an unknown city is a 404, not an empty snapshot")
    void unknownCityIsNotFound() throws Exception {
        Tokens tokens = loginAs("live-404@example.com", RoleName.CITY_OPERATOR);

        // An empty snapshot for a typo'd slug would look like a city with no
        // telemetry, sending someone to debug the pipeline instead of the URL.
        mockMvc.perform(authGet("/api/v1/live/by-slug/atlantis", tokens.accessToken()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Stream tickets
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the stream refuses a request with no ticket")
    void streamRequiresATicket() throws Exception {
        // Forbidden, not Bad Request: the caller's syntax is fine, their
        // authorisation is not.
        mockMvc.perform(get("/api/v1/live/by-slug/" + BENGALURU + "/stream"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the stream refuses a forged ticket")
    void streamRefusesAForgedTicket() throws Exception {
        mockMvc.perform(get("/api/v1/live/by-slug/" + BENGALURU + "/stream")
                        .param("ticket", "not-a-real-ticket"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a ticket cannot be redeemed twice")
    void ticketIsSingleUse() throws Exception {
        Tokens tokens = loginAs("live-ticket@example.com", RoleName.CITY_OPERATOR);
        insertWindow("BLR-WHF", Instant.now().minus(1, ChronoUnit.MINUTES),
                "0.40", "45.00", "NORMAL", 90, "20.00", "NORMAL", 0, 6);

        String ticket = objectMapper.readTree(
                        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/live/by-slug/" + BENGALURU + "/stream-ticket")
                                        .header("Authorization", "Bearer " + tokens.accessToken()))
                                .andExpect(status().isOk())
                                .andReturn().getResponse().getContentAsString())
                .path("data").path("ticket").asText();

        assertThat(ticket).isNotBlank();

        mockMvc.perform(get("/api/v1/live/by-slug/" + BENGALURU + "/stream").param("ticket", ticket))
                .andExpect(status().isOk());

        // Replay must fail even though the ticket is still within its minute:
        // single-use is what bounds the damage if one is captured from a log.
        mockMvc.perform(get("/api/v1/live/by-slug/" + BENGALURU + "/stream").param("ticket", ticket))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a ticket for one city cannot open another city's stream")
    void ticketIsBoundToItsCity() throws Exception {
        Tokens tokens = loginAs("live-crosscity@example.com", RoleName.CITY_OPERATOR);

        String ticket = objectMapper.readTree(
                        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/live/by-slug/" + BENGALURU + "/stream-ticket")
                                        .header("Authorization", "Bearer " + tokens.accessToken()))
                                .andReturn().getResponse().getContentAsString())
                .path("data").path("ticket").asText();

        mockMvc.perform(get("/api/v1/live/by-slug/mumbai/stream").param("ticket", ticket))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("issuing a ticket requires authentication")
    void ticketIssuanceRequiresAuthentication() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/live/by-slug/" + BENGALURU + "/stream-ticket"))
                .andExpect(status().isUnauthorized());
    }
}
