package com.citypulse.telemetry.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Live intelligence payloads (PRD §8, §9).
 *
 * <p>Every numeric field is nullable and every response carries the window it
 * was computed from. That is not defensive padding — the phase's exit criterion
 * is that each displayed figure traces to a warehouse row, so a client must be
 * able to distinguish "measured as zero" from "never measured" and to say when
 * the number is from. A DTO of primitives would collapse both into 0 and lose
 * the timestamp.
 */
public final class TelemetryResponses {

    private TelemetryResponses() {
    }

    /**
     * Conditions for one zone, from its most recent curated window.
     */
    @Schema(description = "Latest curated conditions for a single zone")
    public record ZoneCondition(
            @Schema(description = "Public zone identifier") String zoneId,
            String zoneCode,
            String zoneName,
            String zoneType,
            BigDecimal latitude,
            BigDecimal longitude,

            @Schema(description = "Start of the curated window these values come from")
            Instant windowStart,
            Instant windowEnd,

            Integer vehicleCount,
            BigDecimal averageSpeedKph,
            @Schema(description = "Vehicles present over rated road capacity; may exceed 1.0")
            BigDecimal occupancyRatio,
            @Schema(description = "NORMAL | MODERATE | HIGH | CRITICAL")
            String congestionLevel,

            Integer aqi,
            String aqiCategory,

            BigDecimal temperatureC,
            BigDecimal precipitationMmH,
            String weatherCondition,

            int activeIncidents,
            int activeEvents,

            @Schema(description = "Composite 0-100 risk; null when nothing was measured")
            BigDecimal riskScore,
            String riskLevel,

            @Schema(description = "Raw events behind this window. A low count means a thin sample.")
            int sampleCount,
            @Schema(description = "True when this zone's telemetry is synthetic (PRD §42)")
            boolean demoData,

            @Schema(description = "False when the zone has no recent window at all")
            boolean hasData
    ) {
    }

    /**
     * City-wide KPI tiles (PRD §8).
     *
     * <p>Each figure carries the number of zones it was actually computed from.
     * A city average over three of twenty zones is a different claim from one
     * over all twenty, and the tile has to be able to show that rather than
     * presenting both as "the city average".
     */
    @Schema(description = "Aggregated conditions across a city's monitored zones")
    public record CityKpis(
            BigDecimal averageCongestion,
            BigDecimal averageSpeedKph,
            Long totalVehicleCount,
            Integer averageAqi,
            BigDecimal temperatureC,
            BigDecimal precipitationMmH,
            String weatherCondition,
            int activeIncidents,
            int activeEvents,
            int activeAlerts,
            BigDecimal averageRiskScore,
            String overallRiskLevel,

            @Schema(description = "Zones with a recent window, out of those monitored")
            int zonesReporting,
            int zonesMonitored,
            @Schema(description = "Zones currently in HIGH or CRITICAL condition")
            int zonesDegraded
    ) {
    }

    /**
     * Everything the Command Center needs for one render.
     *
     * <p>Returned as one payload rather than several endpoints because the map,
     * the KPI row and the staleness banner must agree with each other. Fetched
     * separately they would drift by a window and the map could disagree with
     * the tile above it.
     */
    @Schema(description = "A single consistent snapshot of a city's live conditions")
    public record CitySnapshot(
            String cityId,
            String citySlug,
            String cityName,
            String timezone,

            @Schema(description = "Newest curated window in this city; null when nothing has arrived")
            Instant asOf,
            @Schema(description = "Seconds between the newest window and now")
            Long dataAgeSeconds,
            @Schema(description = "True when the newest window is older than the freshness budget")
            boolean stale,

            CityKpis kpis,
            List<ZoneCondition> zones,
            boolean demoData
    ) {
    }

    /** One point in a zone's recent history, for trend charts. */
    @Schema(description = "A single historical window for a zone")
    public record ZoneHistoryPoint(
            Instant windowStart,
            BigDecimal occupancyRatio,
            BigDecimal averageSpeedKph,
            Integer aqi,
            BigDecimal riskScore,
            int activeIncidents,
            int sampleCount
    ) {
    }

    @Schema(description = "A zone's recent curated history, oldest first")
    public record ZoneHistory(
            String zoneId,
            String zoneCode,
            Instant from,
            Instant to,
            int windowCount,
            List<ZoneHistoryPoint> points
    ) {
    }
}
