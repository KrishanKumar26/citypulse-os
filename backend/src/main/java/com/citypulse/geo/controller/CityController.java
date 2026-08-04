package com.citypulse.geo.controller;

import com.citypulse.common.api.ApiResponse;
import com.citypulse.common.api.PageResponse;
import com.citypulse.geo.dto.GeoRequests;
import com.citypulse.geo.dto.GeoResponses;
import com.citypulse.geo.service.CityService;
import com.citypulse.geo.service.ZoneService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cities")
@Validated
@Tag(name = "Cities", description = "City registry and zone hierarchy")
public class CityController {

    private static final int MAX_PAGE_SIZE = 100;

    private final CityService cityService;
    private final ZoneService zoneService;

    public CityController(CityService cityService, ZoneService zoneService) {
        this.cityService = cityService;
        this.zoneService = zoneService;
    }

    @GetMapping
    @Operation(summary = "List cities",
            description = "Requires city:read. Each entry reports whether its telemetry is demo data.")
    public ResponseEntity<ApiResponse<List<GeoResponses.CityResponse>>> list(
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ResponseEntity.ok(ApiResponse.ok(cityService.list(activeOnly)));
    }

    @GetMapping("/{cityId}")
    @Operation(summary = "Get a city", description = "Requires city:read.")
    public ResponseEntity<ApiResponse<GeoResponses.CityResponse>> get(@PathVariable UUID cityId) {
        return ResponseEntity.ok(ApiResponse.ok(cityService.getByUid(cityId)));
    }

    @GetMapping("/by-slug/{slug}")
    @Operation(summary = "Get a city by slug",
            description = "Requires city:read. Supports stable, human-readable links.")
    public ResponseEntity<ApiResponse<GeoResponses.CityResponse>> getBySlug(
            @PathVariable @Size(max = 64) String slug) {
        return ResponseEntity.ok(ApiResponse.ok(cityService.getBySlug(slug)));
    }

    @PostMapping
    @Operation(summary = "Create a city", description = "Requires city:write.")
    public ResponseEntity<ApiResponse<GeoResponses.CityResponse>> create(
            @Valid @RequestBody GeoRequests.CreateCity request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(cityService.create(request), "City created"));
    }

    @PutMapping("/{cityId}")
    @Operation(summary = "Update a city",
            description = "Requires city:write. The slug is immutable once created.")
    public ResponseEntity<ApiResponse<GeoResponses.CityResponse>> update(
            @PathVariable UUID cityId,
            @Valid @RequestBody GeoRequests.UpdateCity request) {
        return ResponseEntity.ok(ApiResponse.ok(cityService.update(cityId, request), "City updated"));
    }

    @DeleteMapping("/{cityId}")
    @Operation(summary = "Soft-delete a city",
            description = "Requires city:write. History referencing the city is preserved.")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID cityId) {
        cityService.delete(cityId);
        return ResponseEntity.ok(ApiResponse.ok("City deleted"));
    }

    // -- Zones nested under their city ------------------------------------

    @GetMapping("/{cityId}/zones")
    @Operation(summary = "List a city's zones",
            description = "Requires zone:read. Unpaginated: the map renders all zones at once, "
                          + "and a city has tens of them.")
    public ResponseEntity<ApiResponse<List<GeoResponses.ZoneResponse>>> listZones(
            @PathVariable UUID cityId,
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ResponseEntity.ok(ApiResponse.ok(zoneService.listByCity(cityId, activeOnly)));
    }

    @GetMapping("/{cityId}/zones/search")
    @Operation(summary = "Search a city's zones", description = "Requires zone:read. Paginated.")
    public ResponseEntity<ApiResponse<PageResponse<GeoResponses.ZoneResponse>>> searchZones(
            @PathVariable UUID cityId,
            @RequestParam(required = false) @Size(max = 120) String search,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "name"));
        return ResponseEntity.ok(ApiResponse.ok(zoneService.search(cityId, search, pageable)));
    }

    @PostMapping("/{cityId}/zones")
    @Operation(summary = "Create a zone", description = "Requires zone:write.")
    public ResponseEntity<ApiResponse<GeoResponses.ZoneResponse>> createZone(
            @PathVariable UUID cityId,
            @Valid @RequestBody GeoRequests.CreateZone request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(zoneService.create(cityId, request), "Zone created"));
    }
}
