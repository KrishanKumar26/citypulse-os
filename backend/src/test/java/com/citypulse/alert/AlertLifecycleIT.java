package com.citypulse.alert;

import com.citypulse.alert.domain.Alert;
import com.citypulse.alert.domain.AlertStatus;
import com.citypulse.alert.repository.AlertRepository;
import com.citypulse.alert.service.AlertEngine;
import com.citypulse.support.IntegrationTest;
import com.citypulse.user.domain.RoleName;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Automatic alerting end to end (PRD §17).
 *
 * <p>The engine normally runs on a timer; here it is invoked directly so a test
 * does not have to sleep through a schedule. Everything else is real — the same
 * rules, the same repository, the same deduplication index.
 */
@DisplayName("Alert lifecycle")
class AlertLifecycleIT extends IntegrationTest {

    @Autowired
    private AlertEngine alertEngine;

    @Autowired
    private AlertRepository alertRepository;

    /** Writes a curated window severe enough to trip several rules at once. */
    private void insertSevereWindow(String zoneCode, Instant windowStart) {
        transactionTemplate.executeWithoutResult(status ->
                entityManager.createNativeQuery("""
                        INSERT INTO zone_metrics (zone_id, window_start, window_end, vehicle_count,
                            average_speed_kph, occupancy_ratio, congestion_level, aqi, aqi_category,
                            temperature_c, precipitation_mm_h, weather_condition,
                            active_incidents, active_events, risk_score, risk_level,
                            sample_count, demo_data, computed_at)
                        SELECT z.id, CAST(:start AS timestamptz),
                               CAST(:start AS timestamptz) + interval '5 minutes',
                               2400, 9.50, 1.6200, 'CRITICAL', 355, 'VERY_POOR',
                               29.5, 18.0, 'HEAVY_RAIN', 4, 1, 88.50, 'CRITICAL', 6, TRUE, now()
                        FROM zones z WHERE z.code = :code
                        """)
                        .setParameter("code", zoneCode)
                        .setParameter("start", windowStart.toString())
                        .executeUpdate());
    }

    private Instant recentWindow() {
        return Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(2, ChronoUnit.MINUTES);
    }

    // ------------------------------------------------------------------
    // Raising
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a severe window raises alerts with no manual trigger")
    void raisesAlertsFromCuratedData() {
        insertSevereWindow("BLR-WHF", recentWindow());

        alertEngine.evaluate();

        List<Alert> alerts = alertRepository.findAll();
        assertThat(alerts).isNotEmpty();
        assertThat(alerts).extracting(Alert::getRuleCode)
                .contains("SEVERE_CONGESTION", "HAZARDOUS_AIR_QUALITY",
                        "MULTIPLE_INCIDENTS", "CRITICAL_COMPOSITE_RISK");
    }

    @Test
    @DisplayName("every raised alert cites the rule, metric and window behind it")
    void alertsCarryProvenance() {
        Instant window = recentWindow();
        insertSevereWindow("BLR-WHF", window);

        alertEngine.evaluate();

        // PRD §15: an alert that cannot be traced back to data is indistinguishable
        // from one the platform invented.
        assertThat(alertRepository.findAll()).allSatisfy(alert -> {
            assertThat(alert.getRuleCode()).isNotBlank();
            assertThat(alert.getMetricName()).isNotBlank();
            assertThat(alert.getObservedValue()).isNotNull();
            assertThat(alert.getThresholdValue()).isNotNull();
            assertThat(alert.getZoneMetricWindowStart()).isEqualTo(window);
            assertThat(alert.getRecommendedAction()).isNotBlank();
            assertThat(alert.getZone()).isNotNull();
            assertThat(alert.getCity()).isNotNull();
        });
    }

    @Test
    @DisplayName("a quiet window raises nothing")
    void quietConditionsRaiseNothing() {
        transactionTemplate.executeWithoutResult(status ->
                entityManager.createNativeQuery("""
                        INSERT INTO zone_metrics (zone_id, window_start, window_end,
                            average_speed_kph, occupancy_ratio, congestion_level, aqi, aqi_category,
                            active_incidents, active_events, risk_score, risk_level,
                            sample_count, demo_data, computed_at)
                        SELECT z.id, now() - interval '2 minutes', now() + interval '3 minutes',
                               46.00, 0.3200, 'NORMAL', 85, 'SATISFACTORY',
                               0, 0, 18.40, 'NORMAL', 6, TRUE, now()
                        FROM zones z WHERE z.code = 'BLR-WHF'
                        """).executeUpdate());

        alertEngine.evaluate();

        assertThat(alertRepository.findAll()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Deduplication
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a persistent condition does not re-raise on every cycle")
    void doesNotDuplicateAcrossCycles() {
        insertSevereWindow("BLR-WHF", recentWindow());

        alertEngine.evaluate();
        long afterFirst = alertRepository.count();

        alertEngine.evaluate();
        alertEngine.evaluate();

        // Alert fatigue is how alerting actually fails: a zone congested for an
        // hour must produce one alert that stays open, not one per evaluation.
        assertThat(alertRepository.count()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("a re-confirmed alert moves forward to the window that confirmed it")
    void refreshesTheOpenAlert() {
        Instant first = recentWindow();
        insertSevereWindow("BLR-WHF", first);
        alertEngine.evaluate();

        Instant second = first.plus(5, ChronoUnit.MINUTES);
        insertSevereWindow("BLR-WHF", second);
        alertEngine.evaluate();

        assertThat(alertRepository.findAll())
                .filteredOn(a -> "SEVERE_CONGESTION".equals(a.getRuleCode()))
                .allSatisfy(a -> assertThat(a.getZoneMetricWindowStart()).isEqualTo(second));
    }

    @Test
    @DisplayName("a condition spanning an hour boundary stays one alert")
    void doesNotReRaiseAcrossTheHourBoundary() {
        // Deliberately straddling :00. The dedupe key used to bucket the window
        // start to the hour, so 08:55 and 09:00 were different conditions and the
        // second raised a second alert — the fatigue deduplication exists to
        // prevent, on a zone that never stopped being congested.
        //
        // The old test could only catch this when it happened to run in the last
        // five minutes of an hour, which is why it survived until one CI run
        // started at 18:57. These two windows straddle the boundary whatever the
        // clock says, and both stay inside telemetry.max-age.
        Instant topOfHour = Instant.now().truncatedTo(ChronoUnit.HOURS);
        Instant before = topOfHour.minus(5, ChronoUnit.MINUTES);

        insertSevereWindow("BLR-WHF", before);
        alertEngine.evaluate();
        long afterFirst = alertRepository.count();

        insertSevereWindow("BLR-WHF", topOfHour);
        alertEngine.evaluate();

        assertThat(alertRepository.count())
                .as("crossing :00 must refresh the open alert, not raise a second one")
                .isEqualTo(afterFirst);
        assertThat(alertRepository.findAll())
                .filteredOn(a -> "SEVERE_CONGESTION".equals(a.getRuleCode()))
                .allSatisfy(a -> assertThat(a.getZoneMetricWindowStart()).isEqualTo(topOfHour));
    }

    @Test
    @DisplayName("two cities sharing a zone code each get their own alert")
    void doesNotCollideAcrossCities() {
        // Zone codes are unique within a city, not globally
        // (uq_zones_city_code_active). A key built from the code alone would give
        // both zones the same identity, and the partial unique index would reject
        // the second insert — which the engine logs as a lost race and drops. A
        // real alert for one city would vanish because another city named a zone
        // the same thing.
        //
        // The demo geography prefixes every code with its city, so this never
        // occurred naturally. That is what kept it hidden, not what made it safe.
        transactionTemplate.executeWithoutResult(status ->
                entityManager.createNativeQuery("""
                        INSERT INTO zones (uid, city_id, code, name, zone_type,
                            center_latitude, center_longitude, area_sq_km, population,
                            road_capacity_vph, active)
                        SELECT gen_random_uuid(), c.id, 'BLR-WHF', 'Same code, other city',
                               'COMMERCIAL', 19.0760, 72.8777, 5.0, 100000, 4000, TRUE
                        FROM cities c WHERE c.slug = 'mumbai'
                        """).executeUpdate());

        Instant window = recentWindow();
        insertSevereWindow("BLR-WHF", window);   // matches both zones now
        alertEngine.evaluate();

        assertThat(alertRepository.findAll())
                .filteredOn(a -> "SEVERE_CONGESTION".equals(a.getRuleCode()))
                .as("each zone is its own condition, whatever it is called")
                .hasSize(2);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    private Alert anOpenAlert() {
        insertSevereWindow("BLR-WHF", recentWindow());
        alertEngine.evaluate();
        return alertRepository.findAll().stream().findFirst().orElseThrow();
    }

    private JsonNode transition(String alertUid, String token, String body) throws Exception {
        String response = mockMvc.perform(patch("/api/v1/alerts/" + alertUid + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    @Test
    @DisplayName("acknowledging records who saw it and when")
    void acknowledgementIsAttributed() throws Exception {
        Alert alert = anOpenAlert();
        Tokens tokens = loginAs("alert-ack@example.com", RoleName.CITY_OPERATOR);

        JsonNode result = transition(alert.getUid().toString(), tokens.accessToken(),
                "{\"status\":\"ACKNOWLEDGED\"}");

        assertThat(result.path("status").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(result.path("acknowledgedBy").asText()).isEqualTo("alert-ack@example.com");
        assertThat(result.path("acknowledgedAt").asText()).isNotBlank();
    }

    @Test
    @DisplayName("moving to INVESTIGATING keeps the original acknowledgement")
    void investigatingPreservesFirstAcknowledgement() throws Exception {
        Alert alert = anOpenAlert();
        Tokens tokens = loginAs("alert-investigate@example.com", RoleName.CITY_OPERATOR);

        String first = transition(alert.getUid().toString(), tokens.accessToken(),
                "{\"status\":\"ACKNOWLEDGED\"}").path("acknowledgedAt").asText();
        String second = transition(alert.getUid().toString(), tokens.accessToken(),
                "{\"status\":\"INVESTIGATING\"}").path("acknowledgedAt").asText();

        // Overwriting would lose who saw it first, which is the question an
        // incident review actually asks.
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("resolving straight from NEW still counts as having been seen")
    void resolvingFromNewRecordsAcknowledgement() throws Exception {
        Alert alert = anOpenAlert();
        Tokens tokens = loginAs("alert-dismiss@example.com", RoleName.CITY_OPERATOR);

        JsonNode result = transition(alert.getUid().toString(), tokens.accessToken(),
                "{\"status\":\"RESOLVED\",\"note\":\"False alarm\"}");

        assertThat(result.path("status").asText()).isEqualTo("RESOLVED");
        assertThat(result.path("acknowledgedAt").asText()).isNotBlank();
        assertThat(result.path("resolutionNote").asText()).isEqualTo("False alarm");
    }

    @Test
    @DisplayName("a resolved alert cannot be reopened")
    void resolvedIsTerminal() throws Exception {
        Alert alert = anOpenAlert();
        Tokens tokens = loginAs("alert-reopen@example.com", RoleName.CITY_OPERATOR);

        transition(alert.getUid().toString(), tokens.accessToken(), "{\"status\":\"RESOLVED\"}");

        // Reopening would make the recorded resolution time false. A recurrence
        // deserves its own alert with its own raised-at.
        mockMvc.perform(patch("/api/v1/alerts/" + alert.getUid() + "/status")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACKNOWLEDGED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the same condition can raise again once the previous alert is resolved")
    void resolvingReleasesTheDedupeKey() throws Exception {
        Alert alert = anOpenAlert();
        Tokens tokens = loginAs("alert-recur@example.com", RoleName.CITY_OPERATOR);
        long openBefore = alertRepository.count();

        transition(alert.getUid().toString(), tokens.accessToken(), "{\"status\":\"RESOLVED\"}");

        // The unique index is partial over open alerts, so the condition holding
        // again produces a fresh record rather than silently reusing a closed one.
        alertEngine.evaluate();
        assertThat(alertRepository.count()).isGreaterThan(openBefore);
    }

    // ------------------------------------------------------------------
    // Authorisation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a viewer can see alerts but cannot work them")
    void viewerCanReadButNotManage() throws Exception {
        Alert alert = anOpenAlert();
        Tokens viewer = loginAs("alert-viewer@example.com", RoleName.VIEWER);

        // The seeded RBAC (migration V2) grants alert:read to VIEWER on purpose:
        // knowing the city is in trouble is not a privileged act. Changing an
        // alert's state is, because it is a claim that someone is handling it.
        mockMvc.perform(authGet("/api/v1/alerts", viewer.accessToken()))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/alerts/" + alert.getUid() + "/status")
                        .header("Authorization", "Bearer " + viewer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACKNOWLEDGED\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("changing an alert's status requires alert:manage")
    void managingRequiresPermission() throws Exception {
        Alert alert = anOpenAlert();
        // ANALYST can read alerts but must not be able to work them.
        Tokens analyst = loginAs("alert-analyst@example.com", RoleName.ANALYST);

        mockMvc.perform(authGet("/api/v1/alerts", analyst.accessToken()))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/alerts/" + alert.getUid() + "/status")
                        .header("Authorization", "Bearer " + analyst.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACKNOWLEDGED\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an unauthenticated caller cannot list alerts")
    void listingRequiresAuthentication() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/alerts"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Auto-resolution
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an alert whose evidence has aged out resolves itself")
    void staleAlertsAutoResolve() {
        // Raised from a window that is already outside the currency bound, so the
        // condition it describes can no longer be true.
        insertSevereWindow("BLR-WHF", recentWindow());
        alertEngine.evaluate();
        assertThat(alertRepository.findAll()).isNotEmpty();

        transactionTemplate.executeWithoutResult(status ->
                entityManager.createNativeQuery(
                                "UPDATE alerts SET zone_metric_window_start = now() - interval '6 hours'")
                        .executeUpdate());

        alertEngine.autoResolveStale();

        // Leaving them open would slowly fill the Alert Center with conditions
        // nobody can act on — the same fatigue problem deduplication solves from
        // the other direction.
        assertThat(alertRepository.findAll())
                .allSatisfy(a -> assertThat(a.getStatus()).isEqualTo(AlertStatus.RESOLVED));
    }
}
