package com.citypulse.response.controller;

import com.citypulse.common.api.ApiResponse;
import com.citypulse.response.dto.ResponseRequests;
import com.citypulse.response.dto.ResponseResponses;
import com.citypulse.response.service.ResponsePlanService;
import com.citypulse.user.domain.Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Response plans — the step between an alert and an action (PRD §16).
 *
 * <p>Reading needs {@code alert:read}: a plan is about a condition, and whoever
 * can see the condition should see what is being done about it. Writing needs
 * {@code alert:manage}, because a plan is an instruction other people will
 * follow.
 */
@RestController
@RequestMapping("/api/v1/response-plans")
@Tag(name = "Response Plans", description = "What we intend to do about a situation")
public class ResponsePlanController {

    private final ResponsePlanService service;

    public ResponsePlanController(ResponsePlanService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.ALERT_READ + "')")
    @Operation(summary = "Response plans for a city, open ones first",
            description = "Requires alert:read. Ordered by status then priority — an operations "
                    + "list is read to find what still needs doing.")
    public ResponseEntity<ApiResponse<List<ResponseResponses.PlanDetail>>> list(
            @RequestParam String citySlug,
            @RequestParam(defaultValue = "true") boolean openOnly) {
        return ResponseEntity.ok(ApiResponse.ok(service.list(citySlug, openOnly)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.ALERT_MANAGE + "')")
    @Operation(summary = "Write a response plan",
            description = "Requires alert:manage. Steps are authored. If an alert is named, its "
                    + "recommendedAction becomes the first step and is flagged as coming from a "
                    + "rule rather than from a person — the platform contributes that one line "
                    + "and no other.")
    public ResponseEntity<ApiResponse<ResponseResponses.PlanDetail>> create(
            @Valid @RequestBody ResponseRequests.Create request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.create(request)));
    }

    @PatchMapping("/{planId}")
    @PreAuthorize("hasAuthority('" + Permissions.ALERT_MANAGE + "')")
    @Operation(summary = "Activate, close, or assign a plan",
            description = "Requires alert:manage.")
    public ResponseEntity<ApiResponse<ResponseResponses.PlanDetail>> updatePlan(
            @PathVariable UUID planId,
            @Valid @RequestBody ResponseRequests.UpdatePlan request) {
        return ResponseEntity.ok(ApiResponse.ok(service.updatePlan(planId, request)));
    }

    @PatchMapping("/{planId}/steps/{stepId}")
    @PreAuthorize("hasAuthority('" + Permissions.ALERT_MANAGE + "')")
    @Operation(summary = "Mark a step done, blocked or skipped",
            description = "Requires alert:manage. Blocking or skipping needs a note: a stalled "
                    + "step without a reason is a dead end nobody can pick up later. A step that "
                    + "produced a measurable action can name the intervention that recorded it.")
    public ResponseEntity<ApiResponse<ResponseResponses.PlanDetail>> updateStep(
            @PathVariable UUID planId,
            @PathVariable UUID stepId,
            @Valid @RequestBody ResponseRequests.UpdateStep request) {
        return ResponseEntity.ok(ApiResponse.ok(service.updateStep(planId, stepId, request)));
    }
}
