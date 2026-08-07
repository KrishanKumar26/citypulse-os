package com.citypulse.telemetry.controller;

import com.citypulse.common.api.ApiResponse;
import com.citypulse.telemetry.dto.DataSourceResponses;
import com.citypulse.telemetry.service.DataSourceService;
import com.citypulse.user.domain.Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The feeds behind everything else (PRD §19).
 *
 * <p>Behind {@code telemetry:read} rather than a management permission: this
 * describes where the numbers on the dashboard came from, which anyone allowed
 * to read the numbers should be able to check. Nothing here is writable, and
 * the source's {@code config} column is not exposed at all.
 */
@RestController
@RequestMapping("/api/v1/data-sources")
@Tag(name = "Data Sources", description = "Ingestion feeds and whether they are delivering")
public class DataSourceController {

    private final DataSourceService service;

    public DataSourceController(DataSourceService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.TELEMETRY_READ + "')")
    @Operation(summary = "Every ingestion source and its recent delivery",
            description = "Requires telemetry:read. Row counts are measured from the event "
                    + "tables over a six-hour window, not read from last_ingested_at, which a "
                    + "retry can touch without any data arriving.")
    public ResponseEntity<ApiResponse<DataSourceResponses.SourceList>> list() {
        return ResponseEntity.ok(ApiResponse.ok(service.list()));
    }

    /**
     * Whether the pipeline behind the numbers is healthy.
     *
     * <p>A different question from whether each feed is delivering: this is what
     * arrived against what was kept. Counted by the loader as it ran, because a
     * validity ratio derived afterwards from the curated tables cannot see a
     * record that was rejected and would always read 100%.
     */
    @GetMapping("/health")
    @PreAuthorize("hasAuthority('" + Permissions.TELEMETRY_READ + "')")
    @Operation(summary = "Pipeline quality: received against kept, rejected, late, and worst lag",
            description = "Requires telemetry:read. Only stages the pipeline instruments appear; "
                    + "a missing stage is uninstrumented, not idle.")
    public ResponseEntity<ApiResponse<DataSourceResponses.PipelineHealth>> health() {
        return ResponseEntity.ok(ApiResponse.ok(service.health()));
    }
}
