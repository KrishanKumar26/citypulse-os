package com.citypulse.intervention.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Interventions and what followed them (PRD §16). */
public final class InterventionResponses {

    private InterventionResponses() {
    }

    /**
     * One metric before and after, with what normal looks like at those hours.
     *
     * <p>`excessChangePct` is the only figure that says anything about the
     * action. The raw change includes whatever the city was going to do anyway —
     * congestion falls in the evening regardless — so the measurement subtracts
     * the movement the baseline already predicted and reports what is left over.
     */
    public record MetricImpact(
            String metric,

            @Schema(description = "Mean over the window before the stated start; null when no "
                    + "curated window existed then")
            BigDecimal before,
            @Schema(description = "Mean over the window after; null when nothing was recorded")
            BigDecimal after,

            @Schema(description = "Observed change, before to after, as a percentage")
            BigDecimal changePct,

            @Schema(description = "What this zone normally reads at these hours of the week")
            BigDecimal baseline,

            @Schema(description = "Change beyond what the baseline already accounts for. Null "
                    + "when there is no baseline for this zone and metric.")
            BigDecimal excessChangePct,

            @Schema(description = "Historical windows the baseline rests on")
            long baselineSamples
    ) {
    }

    public record Impact(
            @Schema(description = "Curated windows found before the start")
            long windowsBefore,
            @Schema(description = "Curated windows found after")
            long windowsAfter,

            @Schema(description = "False when either side has no windows — the intervention "
                    + "cannot be measured, which is different from measuring no effect")
            boolean measurable,
            @Schema(description = "Stated when measurable is false")
            String unmeasurableReason,

            @Schema(description = "True while the intervention is still in effect, so the window "
                    + "after it is still filling")
            boolean provisional,

            List<MetricImpact> metrics
    ) {
    }

    public record InterventionDetail(
            String id,
            String title,
            String description,
            String actionType,

            String zoneId,
            String zoneName,
            String citySlug,

            Instant startedAt,
            Instant endedAt,
            String status,

            String recordedBy,
            int comparisonMinutes,
            String notes,

            @Schema(description = "Null for a city-wide action, which has no zone baseline to "
                    + "compare against")
            Impact impact,

            boolean demoData
    ) {
    }
}
