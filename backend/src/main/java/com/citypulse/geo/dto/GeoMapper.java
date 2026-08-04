package com.citypulse.geo.dto;

import com.citypulse.geo.domain.City;
import com.citypulse.geo.domain.Zone;
import org.springframework.stereotype.Component;

@Component
public class GeoMapper {

    public GeoResponses.CityResponse toCity(City city, long zoneCount) {
        return new GeoResponses.CityResponse(
                city.getUid(),
                city.getSlug(),
                city.getName(),
                city.getCountry(),
                city.getCountryCode(),
                city.getTimezone(),
                city.getCenterLatitude(),
                city.getCenterLongitude(),
                city.getDefaultZoom(),
                city.getPopulation(),
                city.getAreaSqKm(),
                city.isActive(),
                city.isDemoData(),
                zoneCount
        );
    }

    /**
     * The zone inherits its city's {@code demoData} flag: telemetry provenance is
     * a property of the source feeding the city, not of the individual zone, and
     * carrying it here means every zone-level view can label itself without a
     * second lookup.
     */
    public GeoResponses.ZoneResponse toZone(Zone zone) {
        return new GeoResponses.ZoneResponse(
                zone.getUid(),
                zone.getCity().getUid(),
                zone.getCity().getSlug(),
                zone.getCode(),
                zone.getName(),
                zone.getZoneType().name(),
                zone.getCenterLatitude(),
                zone.getCenterLongitude(),
                zone.getAreaSqKm(),
                zone.getPopulation(),
                zone.getRoadCapacityVph(),
                zone.isActive(),
                zone.getCity().isDemoData()
        );
    }

    public GeoResponses.ZoneBoundaryResponse toBoundary(Zone zone) {
        return new GeoResponses.ZoneBoundaryResponse(zone.getUid(), zone.getCode(), zone.getBoundaryGeoJson());
    }
}
