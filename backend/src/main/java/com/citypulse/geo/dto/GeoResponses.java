package com.citypulse.geo.dto;

import java.math.BigDecimal;
import java.util.UUID;

public final class GeoResponses {

    private GeoResponses() {
    }

    /**
     * @param demoData true when this city's telemetry is synthetic. The UI uses it
     *                 to label the data, so simulated readings are never shown as
     *                 live real-world information (PRD §42)
     * @param zoneCount number of active zones, resolved in a single aggregate
     *                  query rather than by loading each city's zones
     */
    public record CityResponse(
            UUID id,
            String slug,
            String name,
            String country,
            String countryCode,
            String timezone,
            BigDecimal centerLatitude,
            BigDecimal centerLongitude,
            int defaultZoom,
            Integer population,
            BigDecimal areaSqKm,
            boolean active,
            boolean demoData,
            long zoneCount
    ) {
    }

    public record ZoneResponse(
            UUID id,
            UUID cityId,
            String citySlug,
            String code,
            String name,
            String zoneType,
            BigDecimal centerLatitude,
            BigDecimal centerLongitude,
            BigDecimal areaSqKm,
            Integer population,
            Integer roadCapacityVph,
            boolean active,
            boolean demoData
    ) {
    }

    /**
     * Zone boundary as GeoJSON, served separately from {@link ZoneResponse}.
     * Polygons are large, and the zone list is rendered as cards and table rows
     * that do not need geometry — including it in every list response would waste
     * bandwidth on every request (PRD §32 of the execution prompt).
     */
    public record ZoneBoundaryResponse(
            UUID id,
            String code,
            String boundaryGeoJson
    ) {
    }
}
