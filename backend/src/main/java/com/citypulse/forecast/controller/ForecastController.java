package com.citypulse.forecast.controller;

import com.citypulse.common.api.ApiResponse;
import com.citypulse.forecast.dto.ForecastResponses;
import com.citypulse.forecast.service.ForecastService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Predicted conditions (PRD §11).
 *
 * <p>Every response carries the measured error behind its confidence, so a
 * caller can judge a prediction rather than take it on trust. The model itself
 * runs in the Python pipeline; this only serves what that produced.
 */
@RestController
@RequestMapping("/api/v1/forecasts")
@Validated
@Tag(name = "Forecasts", description = "Predicted city conditions with measured accuracy")
public class ForecastController {

    private final ForecastService forecastService;

    public ForecastController(ForecastService forecastService) {
        this.forecastService = forecastService;
    }

    @GetMapping("/zones/{zoneId}")
    @Operation(summary = "Forecasts for a zone across all horizons",
            description = "Requires forecast:read. Returns 15, 30, 60, 180 and 360 minute "
                          + "predictions, each with the interval, the confidence derived from "
                          + "that horizon's measured error, and the factors that drove it.")
    public ResponseEntity<ApiResponse<ForecastResponses.ZoneForecast>> forZone(
            @PathVariable UUID zoneId,
            @RequestParam(defaultValue = "occupancy_ratio") String metric) {
        return ResponseEntity.ok(ApiResponse.ok(forecastService.forZone(zoneId, metric)));
    }

    @GetMapping("/cities/{slug}")
    @Operation(summary = "A city's outlook at one horizon",
            description = "Requires forecast:read. What every monitored zone is predicted to look "
                          + "like at the chosen horizon.")
    public ResponseEntity<ApiResponse<ForecastResponses.CityOutlook>> forCity(
            @PathVariable String slug,
            @RequestParam(defaultValue = "occupancy_ratio") String metric,
            @RequestParam(defaultValue = "60") int horizonMinutes) {
        return ResponseEntity.ok(ApiResponse.ok(
                forecastService.forCity(slug, metric, horizonMinutes)));
    }

    @GetMapping("/accuracy")
    @Operation(summary = "How the active model is performing against reality",
            description = "Requires forecast:read. Production error beside the error measured on "
                          + "the training holdout, plus how often actuals landed inside the "
                          + "advertised 95% interval. A widening gap means the model has gone "
                          + "stale — neither number alone can show that.")
    public ResponseEntity<ApiResponse<ForecastResponses.AccuracyReport>> accuracy() {
        return ResponseEntity.ok(ApiResponse.ok(forecastService.accuracy()));
    }
}
