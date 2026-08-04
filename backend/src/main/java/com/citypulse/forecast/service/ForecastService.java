package com.citypulse.forecast.service;

import com.citypulse.common.exception.Exceptions;
import com.citypulse.forecast.domain.Forecast;
import com.citypulse.forecast.domain.ModelMetric;
import com.citypulse.forecast.domain.ModelRun;
import com.citypulse.forecast.dto.ForecastResponses;
import com.citypulse.forecast.repository.ForecastRepository;
import com.citypulse.forecast.repository.ModelMetricRepository;
import com.citypulse.forecast.repository.ModelRunRepository;
import com.citypulse.geo.domain.City;
import com.citypulse.geo.domain.Zone;
import com.citypulse.geo.repository.CityRepository;
import com.citypulse.geo.repository.ZoneRepository;
import com.citypulse.telemetry.config.TelemetryProperties;
import com.citypulse.telemetry.domain.ZoneMetric;
import com.citypulse.telemetry.repository.ZoneMetricRepository;
import com.citypulse.user.domain.Permissions;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Serves stored predictions (PRD §11).
 *
 * <p>Reads only. The model lives in the Python training pipeline, and a
 * prediction served here is always one that was produced deliberately by a job
 * and can therefore be scored later against what actually happened. A backend
 * that computed forecasts on demand would produce numbers nothing ever checks.
 */
@Service
public class ForecastService {

    private static final Logger log = LoggerFactory.getLogger(ForecastService.class);

    /** The model the platform serves. One family for now; see docs/ML.md §9. */
    static final String MODEL_NAME = "traffic-baseline";

    /** Metrics with a stored model, per docs/ML.md §1. */
    private static final List<String> SUPPORTED_METRICS =
            List.of("occupancy_ratio", "average_speed_kph", "vehicle_count", "risk_score");

    private final ForecastRepository forecastRepository;
    private final ModelRunRepository modelRunRepository;
    private final ModelMetricRepository modelMetricRepository;
    private final ZoneRepository zoneRepository;
    private final CityRepository cityRepository;
    private final ZoneMetricRepository zoneMetricRepository;
    private final TelemetryProperties telemetryProperties;
    private final ObjectMapper objectMapper;

    public ForecastService(ForecastRepository forecastRepository,
                           ModelRunRepository modelRunRepository,
                           ModelMetricRepository modelMetricRepository,
                           ZoneRepository zoneRepository,
                           CityRepository cityRepository,
                           ZoneMetricRepository zoneMetricRepository,
                           TelemetryProperties telemetryProperties,
                           ObjectMapper objectMapper) {
        this.forecastRepository = forecastRepository;
        this.modelRunRepository = modelRunRepository;
        this.modelMetricRepository = modelMetricRepository;
        this.zoneRepository = zoneRepository;
        this.cityRepository = cityRepository;
        this.zoneMetricRepository = zoneMetricRepository;
        this.telemetryProperties = telemetryProperties;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // Zone forecast
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.FORECAST_READ + "')")
    public ForecastResponses.ZoneForecast forZone(UUID zoneUid, String metric) {
        validateMetric(metric);

        Zone zone = zoneRepository.findByUidAndDeletedAtIsNull(zoneUid)
                .orElseThrow(() -> new Exceptions.NotFound("Zone", zoneUid));

        List<Forecast> forecasts = forecastRepository.findLatestPerHorizon(zone.getId(), metric);
        if (forecasts.isEmpty()) {
            // An empty list, not a 404: the zone exists and is monitored, there
            // simply are no predictions for it yet. Those are different facts
            // and the UI needs to say different things about them.
            return new ForecastResponses.ZoneForecast(
                    zone.getUid().toString(), zone.getCode(), zone.getName(), metric,
                    currentValue(zone, metric), List.of(), activeModelSummary().orElse(null),
                    zone.getCity().isDemoData());
        }

        Map<String, ModelMetric> errors = errorsFor(forecasts.get(0).getModelRun().getId());

        List<ForecastResponses.ForecastPoint> points = forecasts.stream()
                .map(f -> toPoint(f, errors.get(errorKey(f.getTargetMetric(), f.getHorizonMinutes()))))
                .toList();

        return new ForecastResponses.ZoneForecast(
                zone.getUid().toString(), zone.getCode(), zone.getName(), metric,
                currentValue(zone, metric), points,
                summarise(forecasts.get(0).getModelRun()),
                zone.getCity().isDemoData());
    }

    /**
     * The most recent observed value, so the UI can show where a prediction
     * starts from.
     *
     * <p>A forecast of 0.7 means something quite different depending on whether
     * conditions are at 0.4 or 0.9 now, and showing the prediction alone would
     * hide the direction of travel.
     */
    private BigDecimal currentValue(Zone zone, String metric) {
        Instant notBefore = Instant.now().minus(telemetryProperties.maxAge());
        return zoneMetricRepository.findLatestForZone(zone.getId(), notBefore)
                .map(m -> extract(m, metric))
                .orElse(null);
    }

    private BigDecimal extract(ZoneMetric metric, String target) {
        return switch (target) {
            case "occupancy_ratio" -> metric.getOccupancyRatio();
            case "average_speed_kph" -> metric.getAverageSpeedKph();
            case "vehicle_count" -> metric.getVehicleCount() == null
                    ? null : BigDecimal.valueOf(metric.getVehicleCount());
            case "risk_score" -> metric.getRiskScore();
            default -> null;
        };
    }

    // ------------------------------------------------------------------
    // City outlook
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.FORECAST_READ + "')")
    public ForecastResponses.CityOutlook forCity(String citySlug, String metric, int horizonMinutes) {
        validateMetric(metric);
        validateHorizon(horizonMinutes);

        City city = cityRepository.findBySlugAndDeletedAtIsNull(citySlug)
                .orElseThrow(() -> new Exceptions.NotFound("City", citySlug));

        List<Forecast> forecasts =
                forecastRepository.findLatestForCityAtHorizon(city.getId(), metric, horizonMinutes);

        List<ForecastResponses.ZoneOutlook> zones = forecasts.stream()
                .map(f -> new ForecastResponses.ZoneOutlook(
                        f.getZone().getUid().toString(),
                        f.getZone().getCode(),
                        f.getZone().getName(),
                        f.getZone().getCenterLatitude(),
                        f.getZone().getCenterLongitude(),
                        f.getPredictedValue(),
                        f.getConfidence(),
                        f.getRiskLevel(),
                        f.getTargetTime()))
                .toList();

        int degraded = (int) zones.stream()
                .filter(z -> "HIGH".equals(z.riskLevel()) || "CRITICAL".equals(z.riskLevel()))
                .count();

        return new ForecastResponses.CityOutlook(
                city.getUid().toString(), city.getSlug(), metric, horizonMinutes,
                forecasts.isEmpty() ? null : forecasts.get(0).getTargetTime(),
                zones.size(), degraded, zones,
                forecasts.isEmpty() ? activeModelSummary().orElse(null)
                        : summarise(forecasts.get(0).getModelRun()));
    }

    // ------------------------------------------------------------------
    // Accuracy
    // ------------------------------------------------------------------

    /**
     * How the active model is doing against reality.
     *
     * <p>Exposed as an endpoint rather than kept internal because PRD §11
     * requires accuracy to be tracked, and tracking nobody can see is
     * indistinguishable from not tracking.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.FORECAST_READ + "')")
    public ForecastResponses.AccuracyReport accuracy() {
        ModelRun run = modelRunRepository.findActive(MODEL_NAME)
                .orElseThrow(() -> new Exceptions.NotFound(
                        "No active forecast model. Train one: python -m ml.train"));

        List<ForecastResponses.AccuracyEntry> entries =
                modelMetricRepository.findAccuracyForRun(run.getId()).stream()
                        .map(row -> new ForecastResponses.AccuracyEntry(
                                row.getTargetMetric(),
                                row.getHorizonMinutes(),
                                row.getScoredCount(),
                                row.getProductionMae(),
                                row.getHoldoutMae(),
                                row.getWithinIntervalPct()))
                        .toList();

        return new ForecastResponses.AccuracyReport(summarise(run), entries);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void validateMetric(String metric) {
        if (!SUPPORTED_METRICS.contains(metric)) {
            throw new Exceptions.BadRequest(
                    "No model forecasts '%s'. Available: %s. Crowd intensity and air quality are "
                    + "deliberately not forecast — see docs/ML.md §1."
                            .formatted(metric, String.join(", ", SUPPORTED_METRICS)));
        }
    }

    private void validateHorizon(int minutes) {
        if (!List.of(15, 30, 60, 180, 360).contains(minutes)) {
            throw new Exceptions.BadRequest(
                    "Horizon must be one of 15, 30, 60, 180 or 360 minutes; each is a separately "
                    + "trained model with its own measured error.");
        }
    }

    private Map<String, ModelMetric> errorsFor(Long runId) {
        return modelMetricRepository.findByModelRunIdOrderByTargetMetricAscHorizonMinutesAsc(runId)
                .stream()
                .collect(Collectors.toMap(
                        m -> errorKey(m.getTargetMetric(), m.getHorizonMinutes()),
                        Function.identity()));
    }

    private String errorKey(String metric, int horizon) {
        return metric + ":" + horizon;
    }

    private Optional<ForecastResponses.ModelSummary> activeModelSummary() {
        return modelRunRepository.findActive(MODEL_NAME).map(this::summarise);
    }

    private ForecastResponses.ModelSummary summarise(ModelRun run) {
        return new ForecastResponses.ModelSummary(
                run.getUid().toString(), run.getModelName(), run.getModelVersion(),
                run.getAlgorithm(), run.getTrainedFrom(), run.getTrainedTo(),
                run.getEvaluatedFrom(), run.getEvaluatedTo(),
                run.getTrainingRows(), run.getEvaluationRows());
    }

    private ForecastResponses.ForecastPoint toPoint(Forecast forecast, ModelMetric error) {
        return new ForecastResponses.ForecastPoint(
                forecast.getUid().toString(),
                forecast.getHorizonMinutes(),
                forecast.getTargetTime(),
                forecast.getIssuedAt(),
                forecast.getBasedOnWindow(),
                forecast.getPredictedValue(),
                forecast.getLowerBound(),
                forecast.getUpperBound(),
                forecast.getConfidence(),
                forecast.getRiskLevel(),
                parseFactors(forecast),
                error == null ? null : error.getMae(),
                error == null ? null : error.getBaselineMae(),
                error == null ? null : error.improvementOverBaseline());
    }

    /**
     * Reads the stored explanation.
     *
     * <p>A malformed blob degrades to an empty list rather than failing the
     * request: a forecast without its explanation is still a usable forecast,
     * and refusing to serve it would turn a display problem into an outage.
     */
    private List<ForecastResponses.Factor> parseFactors(Forecast forecast) {
        String raw = forecast.getContributingFactors();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<ForecastResponses.Factor>>() {});
        } catch (Exception e) {
            log.warn("Unreadable contributing_factors on forecast {}: {}",
                    forecast.getUid(), e.getMessage());
            return List.of();
        }
    }
}
