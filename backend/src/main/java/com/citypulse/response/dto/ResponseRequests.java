package com.citypulse.response.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public final class ResponseRequests {

    private ResponseRequests() {
    }

    public record Step(
            @NotBlank @Size(max = 500) String instruction
    ) {
    }

    public record Create(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 1000) String summary,

            @NotNull String citySlug,
            UUID zoneId,

            @Schema(description = "The alert this responds to, if it responds to one. Its "
                    + "recommendedAction becomes the first step, marked as coming from a rule.")
            UUID alertId,

            @Pattern(regexp = "LOW|MEDIUM|HIGH|CRITICAL") String priority,

            @Schema(description = "The steps, in order. At least one — a plan with no steps is "
                    + "a title, and filing it as a response would overstate what exists.")
            @NotEmpty @Valid List<Step> steps
    ) {
    }

    public record UpdateStep(
            @NotNull @Pattern(regexp = "PENDING|DONE|BLOCKED|SKIPPED") String status,

            @Schema(description = "Required when blocking or skipping. A stalled step without a "
                    + "reason is a dead end nobody can pick up later.")
            @Size(max = 500) String note,

            @Schema(description = "The intervention this step produced, when it produced a "
                    + "measurable one. Omit for steps that change no telemetry.")
            UUID interventionId
    ) {
    }

    public record UpdatePlan(
            @Pattern(regexp = "DRAFT|ACTIVE|COMPLETED|CANCELLED") String status,
            @Schema(description = "Who is carrying it out") UUID assignedTo
    ) {
    }
}
