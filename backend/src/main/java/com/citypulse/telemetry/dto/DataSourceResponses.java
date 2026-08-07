package com.citypulse.telemetry.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** What the Data Sources view shows about each feed (PRD §19). */
public final class DataSourceResponses {

    private DataSourceResponses() {
    }

    public record Source(
            String id,
            String code,
            String name,
            String description,
            String sourceType,

            @Schema(description = "SYNTHETIC is first-class, not a fallback — PRD §43 requires "
                    + "the platform to run with no external API")
            String ingestionMode,
            String status,

            @Schema(description = "Null means never delivered, which is a different problem from "
                    + "delivered long ago")
            Instant lastIngestedAt,

            @Schema(description = "Seconds since the last delivery, or null if it never delivered")
            Long secondsSinceLastIngest,

            @Schema(description = "Rows counted in the event tables over the reporting window. "
                    + "Counted rather than taken from last_ingested_at, which a retry can touch "
                    + "without any data arriving.")
            long rowsInWindow,

            @Schema(description = "True when the source is ACTIVE but has delivered nothing in "
                    + "the window — configured to run and not running")
            boolean silent,

            boolean demoData
    ) {
    }

    /**
     * What one pipeline stage did with what it was given.
     *
     * <p>Counted by the loader as it ran. A validity ratio derived afterwards
     * from the curated tables would always read 100%, because a rejected record
     * is not there to be counted.
     */
    public record StageQuality(
            @Schema(description = "VALIDATE, TRANSFORM, LOAD — whichever the pipeline instruments")
            String stage,
            long windows,
            long recordsReceived,
            long recordsValid,
            long recordsRejected,
            long recordsDuplicate,
            long recordsLate,

            @Schema(description = "Valid over received. Null when the stage received nothing, "
                    + "which is not the same as a ratio of zero.")
            BigDecimal validityRatio,

            @Schema(description = "Worst lag between an event happening and being loaded. Null "
                    + "when the pipeline did not record one — absent, not zero.")
            Long maxLagSeconds,

            Instant newestWindowEnd
    ) {
    }

    public record PipelineHealth(
            int windowHours,

            @Schema(description = "Only the stages the pipeline actually writes metrics for. "
                    + "A stage missing here is uninstrumented, not idle.")
            List<StageQuality> stages,

            @Schema(description = "Records the pipeline refused and set aside in this window")
            long deadLettered,

            @Schema(description = "Sources that are ACTIVE and delivered nothing")
            int silentSources,
            int totalSources
    ) {
    }

    public record SourceList(
            @Schema(description = "Hours the row counts cover")
            int windowHours,
            int total,
            int active,
            @Schema(description = "ACTIVE sources that delivered nothing in the window")
            int silent,
            List<Source> sources
    ) {
    }
}
