package com.citypulse.intervention.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class InterventionRequests {

    private InterventionRequests() {
    }

    public record Create(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 1000) String description,

            @Schema(description = "What was done, in your own words — not a fixed vocabulary")
            @NotBlank @Size(max = 64) String actionType,

            @NotNull String citySlug,
            @Schema(description = "Omit for a city-wide action; then no zone baseline applies "
                    + "and no impact is measured")
            UUID zoneId,

            @NotNull Instant startedAt,
            Instant endedAt,

            @Schema(description = "Minutes either side to compare over. A judgement made across "
                    + "ten minutes is not the same claim as one across two hours.")
            @Min(5) @Max(1440) Integer comparisonMinutes,

            @Size(max = 1000) String notes
    ) {
    }
}
