package com.citypulse.geo.domain;

import com.citypulse.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * A monitored area within a city — the unit that telemetry, forecasts, and
 * risk scores are attributed to (PRD §10).
 */
@Entity
@Table(name = "zones")
@Getter
@Setter
@NoArgsConstructor
public class Zone extends BaseEntity {

    /**
     * Lazy: zone lists are rendered without city detail, and eager loading here
     * would produce an N+1 across the map view.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "city_id", nullable = false)
    private City city;

    /** Unique within a city, not globally. */
    @Column(name = "code", nullable = false, length = 48)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "zone_type", nullable = false, length = 24)
    private ZoneType zoneType = ZoneType.MIXED;

    @Column(name = "center_latitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal centerLatitude;

    @Column(name = "center_longitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal centerLongitude;

    /**
     * GeoJSON polygon of the zone boundary, stored as JSON text. Kept as text
     * rather than PostGIS geometry because the MVP performs no spatial queries;
     * migrating to PostGIS later is a column change, not a redesign.
     */
    @Column(name = "boundary_geojson", columnDefinition = "text")
    private String boundaryGeoJson;

    @Column(name = "area_sq_km", precision = 10, scale = 2)
    private BigDecimal areaSqKm;

    @Column(name = "population")
    private Integer population;

    /** Vehicles per hour the zone's road network can carry before congestion. */
    @Column(name = "road_capacity_vph")
    private Integer roadCapacityVph;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
