package com.citypulse.intelligence.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Intelligence payloads (PRD §12, §13, §16).
 *
 * <p>Everything here carries the evidence behind it. The recurring shape is
 * claim + the numbers that produced it + how many observations it rests on,
 * because the alternative is asking a user to trust an assertion — which PRD
 * §15 rules out and which, for a recommendation someone might act on, would be
 * worse than saying nothing.
 */
public final class IntelligenceResponses {

    private IntelligenceResponses() {
    }

    @Schema(description = "A departure from what a zone normally does at this hour")
    public record AnomalyDetail(
            String id,
            String zoneId,
            String zoneCode,
            String zoneName,
            String metric,
            @Schema(description = "SPIKE | DROP | SUSTAINED_SHIFT") String anomalyType,
            String severity,

            Instant windowStart,
            BigDecimal observedValue,
            @Schema(description = "What this zone normally does at this hour of the week")
            BigDecimal baselineValue,
            @Schema(description = "Robust z-score: how many scaled MADs from normal")
            BigDecimal deviationScore,
            BigDecimal percentChange,
            @Schema(description = "Historical windows the baseline rests on")
            int baselineSamples,

            @Schema(description = "Stated at detection time, so it stays true as the code changes")
            String explanation,

            Instant detectedAt,
            boolean demoData
    ) {
    }

    @Schema(description = "A measured co-occurrence between two conditions")
    public record Correlation(
            String conditionA,
            String conditionB,
            @Schema(description = "Readable form, e.g. \"Heavy rain raises the odds of critical congestion\"")
            String statement,
            @Schema(description = "P(B|A)/P(B). Above 1 means A raises the odds of B.")
            BigDecimal lift,
            @Schema(description = "Share of windows with A that also had B")
            BigDecimal confidence,
            int windowsWithA,
            int windowsWithBoth,
            int windowsTotal,
            @Schema(description = "Always false — this is co-occurrence, never a causal claim")
            boolean impliesCausation
    ) {
    }

    @Schema(description = "One past situation and what actually followed it")
    public record RecalledSituation(
            String zoneCode,
            String zoneName,
            Instant occurredAt,
            BigDecimal occupancyAtStart,
            BigDecimal peakOccupancy,
            BigDecimal occupancyChangePct,
            BigDecimal speedChangePct,
            BigDecimal riskChangePct,
            int outcomeHorizonMinutes
    ) {
    }

    /**
     * The answer to "has this happened before".
     *
     * <p>{@code sufficientData} is the important field. When the memory holds
     * too few comparable situations the response says so and the aggregate
     * figures are null — a median over two examples is not a finding, and
     * presenting one would be exactly the guess PRD §15 forbids.
     */
    @Schema(description = "Historical situations matching the current fingerprint")
    public record MemoryRecall(
            @Schema(description = "The fingerprint that was matched")
            String rainBand,
            String dayType,
            String hourBand,
            boolean hadEvent,
            String incidentBand,

            @Schema(description = "False when too few comparable situations exist to say anything")
            boolean sufficientData,
            @Schema(description = "Stated when sufficientData is false")
            String insufficientReason,
            @Schema(description = "True when the exact fingerprint was too rare and the match was widened")
            boolean relaxedMatch,

            int matchCount,

            @Schema(description = "Median occupancy change that followed; null when data is insufficient")
            BigDecimal medianOccupancyChangePct,
            BigDecimal medianSpeedChangePct,
            BigDecimal medianRiskChangePct,

            @Schema(description = "A readable summary of what historically followed")
            String summary,

            List<RecalledSituation> examples
    ) {
    }

    @Schema(description = "What the platform can say about a city right now, with its evidence")
    public record InsightsSummary(
            String citySlug,
            int anomaliesLast24h,
            List<AnomalyDetail> topAnomalies,
            List<Correlation> correlations,
            @Schema(description = "Null when no zone has enough recent data to fingerprint")
            MemoryRecall currentSituation,
            @Schema(description = "Baseline coverage: buckets learned, of those possible")
            int baselineBuckets
    ) {
    }
}
