package com.citypulse.telemetry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One curated window of conditions for one zone — the row the data pipeline
 * writes and the dashboard reads.
 *
 * <p>Deliberately not a {@code BaseEntity}. That superclass carries a public
 * {@code uid} and audited {@code created_at}/{@code updated_at}, which suit
 * records a user creates and edits. A metrics window is neither: it is a
 * computed fact, addressed by (zone, window) rather than by an opaque id, and
 * rewritten wholesale when late data arrives. Giving it a surrogate public
 * identity would imply a lifecycle it does not have.
 *
 * <p>Read-only from the backend's side. The pipeline owns every column here;
 * Spark and the local runner upsert them. Nothing in the application writes to
 * this table, and the absence of setters is what keeps that true.
 */
@Entity
@Table(name = "zone_metrics")
@Getter
public class ZoneMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "zone_id", nullable = false)
    private Long zoneId;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    // --- Traffic -------------------------------------------------------------
    // Every metric is nullable on purpose. A window built from a zone with no
    // air-quality feed has a real traffic reading and no AQI, and the UI has to
    // be able to say "not measured" rather than showing a zero.

    @Column(name = "vehicle_count")
    private Integer vehicleCount;

    @Column(name = "average_speed_kph")
    private BigDecimal averageSpeedKph;

    @Column(name = "occupancy_ratio")
    private BigDecimal occupancyRatio;

    @Column(name = "congestion_level")
    private String congestionLevel;

    // --- Air quality ---------------------------------------------------------

    @Column(name = "aqi")
    private Integer aqi;

    @Column(name = "aqi_category")
    private String aqiCategory;

    /**
     * Where this window's AQI came from: {@code MEASURED} (an instrument),
     * {@code MODELLED} (Copernicus CAMS) or {@code SYNTHETIC} (generated).
     *
     * <p>Nullable, and null is a fourth answer rather than a missing one: the
     * window has no AQI at all. "Not measured" must not render as "generated".
     *
     * <p>Three values rather than the boolean this replaced, because a model of
     * the real atmosphere is neither an instrument nor an invention. Collapsing
     * it either way loses the distinction that matters most to a reader
     * deciding how much to trust the number.
     */
    @Column(name = "aqi_source")
    private String aqiSource;

    // --- Weather -------------------------------------------------------------

    @Column(name = "temperature_c")
    private BigDecimal temperatureC;

    @Column(name = "precipitation_mm_h")
    private BigDecimal precipitationMmH;

    /**
     * Where this window's weather came from: {@code MEASURED} (an instrument),
     * {@code MODELLED} (a numerical weather model) or {@code SYNTHETIC}
     * (generated). Null when the window has no weather reading, which is not
     * the same as a generated one.
     */
    @Column(name = "weather_source")
    private String weatherSource;

    @Column(name = "weather_condition")
    private String weatherCondition;

    // --- Context -------------------------------------------------------------

    @Column(name = "active_incidents", nullable = false)
    private Short activeIncidents;

    @Column(name = "active_events", nullable = false)
    private Short activeEvents;

    // --- Derived risk --------------------------------------------------------

    @Column(name = "risk_score")
    private BigDecimal riskScore;

    @Column(name = "risk_level")
    private String riskLevel;

    /**
     * How many raw events the window was computed from.
     *
     * <p>Surfaced rather than hidden: a window built from two readings is not as
     * trustworthy as one built from sixty, and the UI needs to be able to caveat
     * a thin sample instead of presenting both with equal confidence.
     */
    @Column(name = "sample_count", nullable = false)
    private Integer sampleCount;

    /** PRD §42 — synthetic data stays labelled all the way to the client. */
    @Column(name = "demo_data", nullable = false)
    private boolean demoData;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    protected ZoneMetric() {
        // JPA.
    }

    public ConditionLevel congestion() {
        return ConditionLevel.fromNullable(congestionLevel);
    }

    public ConditionLevel risk() {
        return ConditionLevel.fromNullable(riskLevel);
    }
}
