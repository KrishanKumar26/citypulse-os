package com.citypulse.response.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

public final class ResponseResponses {

    private ResponseResponses() {
    }

    public record StepDetail(
            String id,
            int position,
            String instruction,

            @Schema(description = "True for the single step the platform supplied — the "
                    + "recommendedAction a rule computed. Everything else was typed by a person, "
                    + "and the interface must not present the two in the same voice.")
            boolean fromAlertRule,

            String status,
            String note,
            Instant completedAt,
            String completedBy,

            @Schema(description = "The intervention this step produced, if it produced a "
                    + "measurable one. Null for steps that change no telemetry.")
            String interventionId
    ) {
    }

    public record PlanDetail(
            String id,
            String title,
            String summary,

            String citySlug,
            String zoneId,
            String zoneName,

            @Schema(description = "The alert this responds to, if any")
            String alertId,
            String alertTitle,

            String priority,
            String status,

            String createdBy,
            String assignedTo,

            Instant activatedAt,
            Instant closedAt,
            Instant createdAt,

            @Schema(description = "Steps done, of the total. A plan is rarely all-or-nothing, "
                    + "and one status cannot say three of five with the fourth blocked.")
            int stepsDone,
            int stepsTotal,
            int stepsBlocked,

            List<StepDetail> steps,
            boolean demoData
    ) {
    }
}
