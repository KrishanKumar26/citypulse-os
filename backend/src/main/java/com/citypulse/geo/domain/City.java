package com.citypulse.geo.domain;

import com.citypulse.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "cities")
@Getter
@Setter
@NoArgsConstructor
public class City extends BaseEntity {

    /** Stable URL-safe identifier, e.g. {@code bengaluru}. */
    @Column(name = "slug", nullable = false, length = 64)
    private String slug;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "country", nullable = false, length = 80)
    private String country;

    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    /** IANA zone id. Metrics are stored in UTC and rendered in this zone. */
    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    @Column(name = "center_latitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal centerLatitude;

    @Column(name = "center_longitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal centerLongitude;

    /** Initial map zoom for this city's extent. */
    @Column(name = "default_zoom", nullable = false)
    private int defaultZoom = 11;

    @Column(name = "population")
    private Integer population;

    @Column(name = "area_sq_km", precision = 10, scale = 2)
    private BigDecimal areaSqKm;

    /** False hides the city from selection without deleting its history. */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    /**
     * True when this city's telemetry comes from the synthetic generator rather
     * than a live source. Surfaced to the UI so demo data is always labelled
     * as such (PRD §42).
     */
    @Column(name = "demo_data", nullable = false)
    private boolean demoData = true;
}
