package com.citypulse.alert.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Alert Center payloads (PRD §17).
 *
 * <p>The provenance block — rule, metric, observed and threshold values, and the
 * window they came from — is part of the response rather than an internal
 * detail. An alert a user cannot interrogate is one they have to take on faith,
 * and PRD §15 requires the platform to cite the data behind what it says.
 */
public final class AlertResponses {

    private AlertResponses() {
    }

    @Schema(description = "A raised alert with the measurement that produced it")
    public record AlertDetail(
            String id,
            String alertType,
            String severity,
            String status,
            String title,
            String description,

            @Schema(description = "Null for platform-wide alerts that concern no single place")
            String zoneId,
            String zoneCode,
            String zoneName,
            String cityId,
            String citySlug,

            // --- Provenance ---
            @Schema(description = "The rule that fired")
            String ruleCode,
            @Schema(description = "The curated field that triggered it")
            String metricName,
            BigDecimal observedValue,
            BigDecimal thresholdValue,
            @Schema(description = "The exact curated window this was computed from")
            Instant windowStart,

            String recommendedAction,

            Instant raisedAt,
            Instant acknowledgedAt,
            String acknowledgedBy,
            Instant resolvedAt,
            String resolvedBy,
            String resolutionNote,

            @Schema(description = "True when derived from synthetic telemetry (PRD §42)")
            boolean demoData
    ) {
    }

    @Schema(description = "Counts by severity for the alert badge and summary row")
    public record AlertSummary(
            int total,
            int critical,
            int high,
            int medium,
            int low,
            int unacknowledged
    ) {
    }
}
