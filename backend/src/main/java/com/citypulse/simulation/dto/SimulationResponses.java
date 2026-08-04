package com.citypulse.simulation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Simulation payloads (PRD §14).
 *
 * <p>Every response carries {@code baselineWindow} and {@code engineVersion}.
 * A result read a week later has to be able to say what it departed from and
 * under which assumptions, or the percentages are unanchored.
 */
public final class SimulationResponses {

    private SimulationResponses() {
    }

    @Schema(description = "An action the outcome suggests, tied to the zone and reason behind it")
    public record Recommendation(
            String action,
            @Schema(description = "Why the engine suggests this") String reason,
            @Schema(description = "Zone this applies to; null when city-wide") String zoneCode,
            @Schema(description = "HIGH | MEDIUM | LOW") String priority
    ) {
    }

    @Schema(description = "One zone before and after the scenario")
    public record ZoneImpact(
            String zoneId,
            String zoneCode,
            String zoneName,
            BigDecimal latitude,
            BigDecimal longitude,

            BigDecimal baselineOccupancy,
            BigDecimal simulatedOccupancy,
            BigDecimal baselineSpeedKph,
            BigDecimal simulatedSpeedKph,
            BigDecimal baselineRiskScore,
            BigDecimal simulatedRiskScore,
            String baselineCongestion,
            String simulatedCongestion,

            BigDecimal delayChangeMin,
            BigDecimal parkingChangePct,
            BigDecimal crowdChangePct,

            @Schema(description = "DIRECT when the scenario named this zone, SPILLOVER when the "
                                  + "engine inferred the effect from proximity, CITYWIDE otherwise. "
                                  + "An inferred effect deserves less confidence than a stated one.")
            String impactSource
    ) {
    }

    @Schema(description = "A stored scenario and its full outcome")
    public record SimulationDetail(
            String id,
            String name,
            String description,
            String citySlug,
            Instant createdAt,

            @Schema(description = "The curated window the counterfactual departed from")
            Instant baselineWindow,
            @Schema(description = "Which set of engine assumptions produced this")
            String engineVersion,
            Integer computedMs,

            BigDecimal trafficChangePct,
            BigDecimal crowdChangePct,
            BigDecimal parkingChangePct,
            BigDecimal delayChangeMin,
            BigDecimal baselineRisk,
            BigDecimal simulatedRisk,
            int zonesAffected,

            List<ZoneImpact> zones,
            List<Recommendation> recommendations,

            @Schema(description = "True when the baseline this departed from was synthetic. "
                                  + "A simulation built on generated telemetry produces generated "
                                  + "conclusions, and PRD §42 requires that to be visible wherever "
                                  + "it appears — not only on the readings it started from.")
            boolean demoData
    ) {
    }

    @Schema(description = "A saved scenario, for the history list")
    public record SimulationSummary(
            String id,
            String name,
            String description,
            Instant createdAt,
            Instant baselineWindow,
            String engineVersion,
            BigDecimal trafficChangePct,
            BigDecimal delayChangeMin,
            BigDecimal baselineRisk,
            BigDecimal simulatedRisk,
            int zonesAffected,
            boolean demoData
    ) {
    }
}
