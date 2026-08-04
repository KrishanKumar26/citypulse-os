package com.citypulse.forecast.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Forecast payloads (PRD §11).
 *
 * <p>Every prediction ships with the evidence needed to judge it: the interval
 * around it, the confidence derived from measured error, the factors that drove
 * it, and — on request — the error the producing model actually achieved on a
 * holdout. A number alone would ask the reader to trust it, which PRD §15 rules
 * out.
 */
public final class ForecastResponses {

    private ForecastResponses() {
    }

    @Schema(description = "One feature's contribution to a prediction")
    public record Factor(
            @Schema(description = "Readable name, e.g. \"the evening peak\"") String factor,
            @Schema(description = "The underlying feature") String feature,
            BigDecimal value,
            @Schema(description = "increases | decreases") String direction,
            @Schema(description = "Signed effect on the prediction, in standardised units")
            BigDecimal effect
    ) {
    }

    @Schema(description = "A prediction at one horizon, with the evidence behind it")
    public record ForecastPoint(
            String id,
            int horizonMinutes,
            Instant targetTime,
            Instant issuedAt,
            @Schema(description = "The last observed window this was based on")
            Instant basedOnWindow,

            BigDecimal predictedValue,
            BigDecimal lowerBound,
            BigDecimal upperBound,
            @Schema(description = "Derived from the model's measured error for this metric and horizon")
            BigDecimal confidence,
            @Schema(description = "Null for metrics with no severity scale, such as speed")
            String riskLevel,

            List<Factor> contributingFactors,

            @Schema(description = "Mean absolute error this model achieved on held-out data")
            BigDecimal measuredMae,
            @Schema(description = "Error of the naive no-change prediction, for comparison")
            BigDecimal baselineMae,
            @Schema(description = "How much better than doing nothing, as a percentage")
            BigDecimal improvementOverBaseline
    ) {
    }

    @Schema(description = "Every horizon for one zone and metric")
    public record ZoneForecast(
            String zoneId,
            String zoneCode,
            String zoneName,
            String targetMetric,
            @Schema(description = "The most recent observed value, for comparison with the predictions")
            BigDecimal currentValue,
            List<ForecastPoint> horizons,
            ModelSummary model,
            boolean demoData
    ) {
    }

    @Schema(description = "The model that produced these predictions")
    public record ModelSummary(
            String id,
            String name,
            String version,
            String algorithm,
            @Schema(description = "Training period; the holdout starts where this ends")
            Instant trainedFrom,
            Instant trainedTo,
            @Schema(description = "Evaluation period — strictly after training, never overlapping")
            Instant evaluatedFrom,
            Instant evaluatedTo,
            int trainingRows,
            int evaluationRows
    ) {
    }

    @Schema(description = "One zone's predicted condition, for the city forecast map")
    public record ZoneOutlook(
            String zoneId,
            String zoneCode,
            String zoneName,
            BigDecimal latitude,
            BigDecimal longitude,
            BigDecimal predictedValue,
            BigDecimal confidence,
            String riskLevel,
            Instant targetTime
    ) {
    }

    @Schema(description = "What a city is expected to look like at one horizon")
    public record CityOutlook(
            String cityId,
            String citySlug,
            String targetMetric,
            int horizonMinutes,
            Instant targetTime,
            int zonesForecast,
            @Schema(description = "Zones predicted to reach HIGH or CRITICAL")
            int zonesDegraded,
            List<ZoneOutlook> zones,
            ModelSummary model
    ) {
    }

    @Schema(description = "Measured error, holdout against production")
    public record AccuracyEntry(
            String targetMetric,
            int horizonMinutes,
            long scoredCount,
            @Schema(description = "Mean absolute error on predictions since deployment")
            BigDecimal productionMae,
            @Schema(description = "Mean absolute error measured on the temporal holdout")
            BigDecimal holdoutMae,
            @Schema(description = "Share of actuals that fell inside the advertised 95% interval")
            BigDecimal withinIntervalPct
    ) {
    }

    @Schema(description = "How the active model is performing against reality")
    public record AccuracyReport(
            ModelSummary model,
            @Schema(description = "Empty until forecasts' target times have passed and been scored")
            List<AccuracyEntry> entries
    ) {
    }
}
