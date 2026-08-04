package com.citypulse.forecast;

import com.citypulse.support.IntegrationTest;
import com.citypulse.user.domain.RoleName;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The forecast API (PRD §11).
 *
 * <p>What is asserted is mostly *provenance*, because the phase's exit criterion
 * is that confidence be derived from measured error rather than asserted. A test
 * that only checked a prediction came back would pass equally well against a
 * hardcoded number.
 */
@DisplayName("Forecast API")
class ForecastApiIT extends IntegrationTest {

    /** Seeds a model run, its measured error, and a forecast that cites both. */
    private void seedForecast(String zoneCode, String metric, int horizon,
                              String predicted, String confidence, String mae, String baselineMae) {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery("""
                    INSERT INTO model_runs (uid, model_name, model_version, algorithm,
                        trained_from, trained_to, evaluated_from, evaluated_to,
                        training_rows, evaluation_rows, features, hyperparameters, status)
                    VALUES (gen_random_uuid(), 'traffic-baseline', 'test-v1', 'ridge-regression',
                        now() - interval '28 days', now() - interval '7 days',
                        now() - interval '7 days', now(),
                        100000, 40000, '["lag_5min"]'::jsonb, '{}'::jsonb, 'ACTIVE')
                    ON CONFLICT DO NOTHING
                    """).executeUpdate();

            entityManager.createNativeQuery("""
                    INSERT INTO model_metrics (model_run_id, target_metric, horizon_minutes,
                        mae, mape, rmse, baseline_mae, sample_count)
                    SELECT r.id, :metric, :horizon, CAST(:mae AS numeric), 12.5,
                           CAST(:mae AS numeric), CAST(:baseline AS numeric), 40000
                    FROM model_runs r WHERE r.status = 'ACTIVE'
                    ON CONFLICT DO NOTHING
                    """)
                    .setParameter("metric", metric)
                    .setParameter("horizon", horizon)
                    .setParameter("mae", mae)
                    .setParameter("baseline", baselineMae)
                    .executeUpdate();

            entityManager.createNativeQuery("""
                    INSERT INTO forecasts (uid, zone_id, model_run_id, target_metric,
                        horizon_minutes, issued_at, target_time, based_on_window,
                        predicted_value, lower_bound, upper_bound, confidence,
                        risk_level, contributing_factors)
                    SELECT gen_random_uuid(), z.id, r.id, :metric, :horizon,
                           now(), now() + CAST(:horizon || ' minutes' AS interval), now(),
                           CAST(:predicted AS numeric),
                           CAST(:predicted AS numeric) - 0.1, CAST(:predicted AS numeric) + 0.1,
                           CAST(:confidence AS numeric), 'MODERATE',
                           '[{"factor":"time of day","feature":"hour_sin","value":0.5,
                              "direction":"increases","effect":0.12}]'::jsonb
                    FROM zones z CROSS JOIN model_runs r
                    WHERE z.code = :code AND r.status = 'ACTIVE'
                    """)
                    .setParameter("code", zoneCode)
                    .setParameter("metric", metric)
                    .setParameter("horizon", horizon)
                    .setParameter("predicted", predicted)
                    .setParameter("confidence", confidence)
                    .executeUpdate();
        });
    }

    private String zoneUid(String code) {
        return transactionTemplate.execute(status -> (String) entityManager
                .createNativeQuery("SELECT uid::text FROM zones WHERE code = :code")
                .setParameter("code", code)
                .getSingleResult());
    }

    private JsonNode forecastFor(String zoneCode, String metric, String token) throws Exception {
        String body = mockMvc.perform(authGet(
                        "/api/v1/forecasts/zones/" + zoneUid(zoneCode) + "?metric=" + metric, token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("a forecast carries the measured error its confidence came from")
    void forecastCitesMeasuredError() throws Exception {
        Tokens tokens = loginAs("forecast-read@example.com", RoleName.CITY_OPERATOR);
        seedForecast("BLR-WHF", "occupancy_ratio", 60, "0.6500", "0.8732", "0.1268", "0.1945");

        JsonNode point = forecastFor("BLR-WHF", "occupancy_ratio", tokens.accessToken())
                .path("horizons").get(0);

        // The exit criterion: a caller can see the error behind the confidence
        // and the naive baseline it was measured against.
        assertThat(point.path("confidence").decimalValue()).isEqualByComparingTo("0.8732");
        assertThat(point.path("measuredMae").decimalValue()).isEqualByComparingTo("0.1268");
        assertThat(point.path("baselineMae").decimalValue()).isEqualByComparingTo("0.1945");
        assertThat(point.path("improvementOverBaseline").asDouble()).isGreaterThan(0);
    }

    @Test
    @DisplayName("a forecast carries the factors that produced it")
    void forecastCitesItsFactors() throws Exception {
        Tokens tokens = loginAs("forecast-factors@example.com", RoleName.CITY_OPERATOR);
        seedForecast("BLR-WHF", "occupancy_ratio", 60, "0.6500", "0.8732", "0.1268", "0.1945");

        JsonNode factors = forecastFor("BLR-WHF", "occupancy_ratio", tokens.accessToken())
                .path("horizons").get(0).path("contributingFactors");

        // PRD §15: a prediction the UI cannot explain is one the user has to
        // take on faith.
        assertThat(factors).isNotEmpty();
        assertThat(factors.get(0).path("factor").asText()).isNotBlank();
        assertThat(factors.get(0).path("direction").asText()).isIn("increases", "decreases");
    }

    @Test
    @DisplayName("the model's evaluation period starts where its training ended")
    void modelReportsAnHonestHoldout() throws Exception {
        Tokens tokens = loginAs("forecast-model@example.com", RoleName.CITY_OPERATOR);
        seedForecast("BLR-WHF", "occupancy_ratio", 60, "0.6500", "0.8732", "0.1268", "0.1945");

        JsonNode model = forecastFor("BLR-WHF", "occupancy_ratio", tokens.accessToken()).path("model");

        // A model evaluated on data it trained on looks excellent and forecasts
        // nothing; exposing both windows is what lets a reader rule that out.
        Instant trainedTo = Instant.parse(model.path("trainedTo").asText());
        Instant evaluatedFrom = Instant.parse(model.path("evaluatedFrom").asText());
        assertThat(evaluatedFrom).isAfterOrEqualTo(trainedTo.truncatedTo(ChronoUnit.SECONDS));
        assertThat(model.path("algorithm").asText()).isNotBlank();
    }

    @Test
    @DisplayName("a zone with no predictions returns an empty list, not a 404")
    void zoneWithoutForecastsIsNotAnError() throws Exception {
        Tokens tokens = loginAs("forecast-empty@example.com", RoleName.CITY_OPERATOR);

        JsonNode data = forecastFor("BLR-WHF", "occupancy_ratio", tokens.accessToken());

        // The zone exists and is monitored; there simply are no predictions yet.
        // Those are different facts and the UI must say different things.
        assertThat(data.path("horizons")).isEmpty();
        assertThat(data.path("zoneCode").asText()).isEqualTo("BLR-WHF");
    }

    @Test
    @DisplayName("an unforecastable metric is refused with a reason")
    void unsupportedMetricIsRejected() throws Exception {
        Tokens tokens = loginAs("forecast-badmetric@example.com", RoleName.CITY_OPERATOR);

        // Crowd intensity has no sensor, so no model exists. Returning an empty
        // forecast would imply the platform tried and found nothing.
        mockMvc.perform(authGet(
                        "/api/v1/forecasts/zones/" + zoneUid("BLR-WHF") + "?metric=crowd_intensity",
                        tokens.accessToken()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an untrained horizon is refused")
    void unsupportedHorizonIsRejected() throws Exception {
        Tokens tokens = loginAs("forecast-badhorizon@example.com", RoleName.CITY_OPERATOR);

        mockMvc.perform(authGet(
                        "/api/v1/forecasts/cities/bengaluru?horizonMinutes=45", tokens.accessToken()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("reading forecasts requires forecast:read")
    void requiresPermission() throws Exception {
        mockMvc.perform(get("/api/v1/forecasts/accuracy"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the city outlook counts zones predicted to degrade")
    void cityOutlookCountsDegradedZones() throws Exception {
        Tokens tokens = loginAs("forecast-city@example.com", RoleName.CITY_OPERATOR);
        seedForecast("BLR-WHF", "occupancy_ratio", 60, "0.6500", "0.8732", "0.1268", "0.1945");

        String body = mockMvc.perform(authGet(
                        "/api/v1/forecasts/cities/bengaluru?horizonMinutes=60", tokens.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).path("data");

        assertThat(data.path("zonesForecast").asInt()).isPositive();
        assertThat(data.path("horizonMinutes").asInt()).isEqualTo(60);
    }

    @Test
    @DisplayName("accuracy is empty rather than fabricated before anything is scored")
    void accuracyIsEmptyUntilScored() throws Exception {
        Tokens tokens = loginAs("forecast-accuracy@example.com", RoleName.CITY_OPERATOR);
        seedForecast("BLR-WHF", "occupancy_ratio", 60, "0.6500", "0.8732", "0.1268", "0.1945");

        String body = mockMvc.perform(authGet("/api/v1/forecasts/accuracy", tokens.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).path("data");

        // The model exists, so it is described; no forecast's target time has
        // passed, so there is nothing measured to report yet.
        assertThat(data.path("model").path("version").asText()).isEqualTo("test-v1");
        assertThat(data.path("entries")).isEmpty();
    }
}
