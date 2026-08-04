package com.citypulse.geo.controller;

import com.citypulse.common.api.ApiResponse;
import com.citypulse.geo.dto.GeoRequests;
import com.citypulse.geo.dto.GeoResponses;
import com.citypulse.geo.service.ZoneService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Operations on a zone by its own identifier. Creation and listing live under
 * {@code /cities/{cityId}/zones}, because a zone only exists within a city.
 */
@RestController
@RequestMapping("/api/v1/zones")
@Validated
@Tag(name = "Zones", description = "Monitored areas within a city")
public class ZoneController {

    private final ZoneService zoneService;

    public ZoneController(ZoneService zoneService) {
        this.zoneService = zoneService;
    }

    @GetMapping("/{zoneId}")
    @Operation(summary = "Get a zone", description = "Requires zone:read.")
    public ResponseEntity<ApiResponse<GeoResponses.ZoneResponse>> get(@PathVariable UUID zoneId) {
        return ResponseEntity.ok(ApiResponse.ok(zoneService.get(zoneId)));
    }

    @GetMapping("/{zoneId}/boundary")
    @Operation(summary = "Get a zone's boundary geometry",
            description = "Requires zone:read. Served separately from the zone so list responses "
                          + "do not carry polygon data.")
    public ResponseEntity<ApiResponse<GeoResponses.ZoneBoundaryResponse>> getBoundary(
            @PathVariable UUID zoneId) {
        return ResponseEntity.ok(ApiResponse.ok(zoneService.getBoundary(zoneId)));
    }

    @PutMapping("/{zoneId}")
    @Operation(summary = "Update a zone",
            description = "Requires zone:write. The zone code is immutable once created.")
    public ResponseEntity<ApiResponse<GeoResponses.ZoneResponse>> update(
            @PathVariable UUID zoneId,
            @Valid @RequestBody GeoRequests.UpdateZone request) {
        return ResponseEntity.ok(ApiResponse.ok(zoneService.update(zoneId, request), "Zone updated"));
    }

    @DeleteMapping("/{zoneId}")
    @Operation(summary = "Soft-delete a zone",
            description = "Requires zone:write. Historical metrics referencing the zone are preserved.")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID zoneId) {
        zoneService.delete(zoneId);
        return ResponseEntity.ok(ApiResponse.ok("Zone deleted"));
    }
}
