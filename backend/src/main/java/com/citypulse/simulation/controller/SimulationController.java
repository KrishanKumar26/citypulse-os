package com.citypulse.simulation.controller;

import com.citypulse.common.api.ApiResponse;
import com.citypulse.common.api.PageResponse;
import com.citypulse.simulation.dto.ScenarioRequests;
import com.citypulse.simulation.dto.SimulationResponses;
import com.citypulse.simulation.service.SimulationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The What-If Simulator (PRD §14).
 *
 * <p>Results are counterfactuals produced by a stated model, never predictions
 * of what will happen. The engine's assumptions are documented in
 * {@code ScenarioEngine} and unit tested, and every response carries the
 * baseline window and engine version that produced it.
 */
@RestController
@RequestMapping("/api/v1/simulations")
@Validated
@Tag(name = "Simulations", description = "Hypothetical scenarios run against observed conditions")
public class SimulationController {

    private final SimulationService simulationService;

    public SimulationController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @PostMapping
    @Operation(summary = "Run a scenario",
            description = "Requires simulation:create. Computes and stores the outcome "
                          + "synchronously — a scenario over a city's zones is arithmetic over a "
                          + "few dozen rows, so a job queue would add latency and a failure mode "
                          + "for nothing.")
    public ResponseEntity<ApiResponse<SimulationResponses.SimulationDetail>> run(
            @Valid @RequestBody ScenarioRequests.RunScenario request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.ok(simulationService.run(request), "Simulation completed"));
    }

    @GetMapping("/{simulationId}")
    @Operation(summary = "Reload a saved scenario",
            description = "Requires simulation:read. Includes the baseline window and engine "
                          + "version, so an old result stays interpretable.")
    public ResponseEntity<ApiResponse<SimulationResponses.SimulationDetail>> get(
            @PathVariable UUID simulationId) {
        return ResponseEntity.ok(ApiResponse.ok(simulationService.get(simulationId)));
    }

    @GetMapping
    @Operation(summary = "Scenario history for a city",
            description = "Requires simulation:read. Newest first.")
    public ResponseEntity<ApiResponse<PageResponse<SimulationResponses.SimulationSummary>>> list(
            @RequestParam String citySlug,
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(simulationService.list(citySlug, pageable)));
    }
}
