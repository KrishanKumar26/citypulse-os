package com.citypulse.alert.controller;

import com.citypulse.alert.domain.AlertStatus;
import com.citypulse.alert.dto.AlertResponses;
import com.citypulse.alert.service.AlertService;
import com.citypulse.common.api.ApiResponse;
import com.citypulse.common.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The Alert Center (PRD §17).
 */
@RestController
@RequestMapping("/api/v1/alerts")
@Validated
@Tag(name = "Alerts", description = "Automatically raised city conditions and their lifecycle")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping
    @Operation(summary = "List alerts",
            description = "Requires alert:read. Defaults to open alerts only, most severe first — "
                          + "the view an operator actually needs, rather than every alert ever raised.")
    public ResponseEntity<ApiResponse<PageResponse<AlertResponses.AlertDetail>>> list(
            @RequestParam(required = false) UUID cityId,
            @RequestParam(required = false) AlertStatus status,
            @RequestParam(defaultValue = "true") boolean openOnly,
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(
                alertService.list(cityId, status, openOnly, pageable)));
    }

    @GetMapping("/summary")
    @Operation(summary = "Open alert counts by severity",
            description = "Requires alert:read. Drives the alert badge without fetching every row.")
    public ResponseEntity<ApiResponse<AlertResponses.AlertSummary>> summary(
            @RequestParam(required = false) UUID cityId) {
        return ResponseEntity.ok(ApiResponse.ok(alertService.summary(cityId)));
    }

    @GetMapping("/{alertId}")
    @Operation(summary = "Get an alert",
            description = "Requires alert:read. Includes the rule, metric and curated window "
                          + "the alert was raised from.")
    public ResponseEntity<ApiResponse<AlertResponses.AlertDetail>> get(@PathVariable UUID alertId) {
        return ResponseEntity.ok(ApiResponse.ok(alertService.get(alertId)));
    }

    @PatchMapping("/{alertId}/status")
    @Operation(summary = "Acknowledge, investigate or resolve an alert",
            description = "Requires alert:manage. A resolved alert cannot be reopened — a "
                          + "recurrence raises a new one so its own timeline stays honest.")
    public ResponseEntity<ApiResponse<AlertResponses.AlertDetail>> transition(
            @PathVariable UUID alertId,
            @RequestBody StatusChange request) {
        return ResponseEntity.ok(ApiResponse.ok(
                alertService.transition(alertId, request.status(), request.note()),
                "Alert updated"));
    }

    /**
     * @param status the state to move to
     * @param note   optional context, stored as the resolution note
     */
    public record StatusChange(AlertStatus status, @Size(max = 500) String note) {
    }
}
