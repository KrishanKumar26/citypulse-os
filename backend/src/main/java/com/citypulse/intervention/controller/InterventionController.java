package com.citypulse.intervention.controller;

import com.citypulse.common.api.ApiResponse;
import com.citypulse.intervention.dto.InterventionRequests;
import com.citypulse.intervention.dto.InterventionResponses;
import com.citypulse.intervention.service.InterventionService;
import com.citypulse.user.domain.Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Actions taken, and what the city did afterwards (PRD §16).
 *
 * <p>The last stage of the loop the rest of the product builds toward: having
 * observed, understood, predicted and simulated, find out whether acting helped.
 *
 * <p>Reading is behind {@code telemetry:read} — anyone who can see the numbers
 * should be able to see what was done about them. Recording one requires
 * {@code alert:manage}, because it is a claim that will be attributed to the
 * person making it and measured against the city's own history.
 */
@RestController
@RequestMapping("/api/v1/interventions")
@Tag(name = "Interventions", description = "Actions taken and their measured aftermath")
public class InterventionController {

    private final InterventionService service;

    public InterventionController(InterventionService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.TELEMETRY_READ + "')")
    @Operation(summary = "Interventions recorded for a city, newest first",
            description = "Requires telemetry:read. Each carries what followed it, measured "
                    + "against the zone's own baseline for the hours involved — not a raw "
                    + "before/after difference, which would credit an action with whatever the "
                    + "city was going to do anyway.")
    public ResponseEntity<ApiResponse<List<InterventionResponses.InterventionDetail>>> list(
            @RequestParam String citySlug) {
        return ResponseEntity.ok(ApiResponse.ok(service.listForCity(citySlug)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.ALERT_MANAGE + "')")
    @Operation(summary = "Record an action that was taken",
            description = "Requires alert:manage. The platform cannot observe an intervention; "
                    + "this records that someone says one happened, and attributes it to them.")
    public ResponseEntity<ApiResponse<InterventionResponses.InterventionDetail>> create(
            @Valid @RequestBody InterventionRequests.Create request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.record(request)));
    }
}
