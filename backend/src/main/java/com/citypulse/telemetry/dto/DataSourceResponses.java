package com.citypulse.telemetry.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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
