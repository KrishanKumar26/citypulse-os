package com.citypulse.alert.domain;

import com.citypulse.geo.domain.City;
import com.citypulse.geo.domain.Zone;
import com.citypulse.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A raised alert (PRD §17).
 *
 * <p>Alerts are derived from curated metrics rather than ingested, which makes
 * provenance the important property: {@code ruleCode}, {@code metricName},
 * {@code observedValue}, {@code thresholdValue} and
 * {@code zoneMetricWindowStart} together let the UI show why this fired and
 * link back to the exact window it fired on. An alert that cannot be traced to
 * a row is the invented fact PRD §15 forbids.
 *
 * <p>Not a {@code BaseEntity}: alerts need {@code raised_at} as their own
 * timeline anchor rather than an audit {@code created_at}, and the two would sit
 * awkwardly together — the moment a condition became true is a domain fact, not
 * bookkeeping.
 */
@Entity
@Table(name = "alerts")
@Getter
@Setter
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uid", nullable = false, unique = true, updatable = false)
    private UUID uid = UUID.randomUUID();

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false)
    private AlertType alertType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private AlertSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AlertStatus status = AlertStatus.NEW;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", nullable = false)
    private String description;

    // Nullable: SYSTEM and DATA_QUALITY alerts concern the platform, not a place.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zone_id")
    private Zone zone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "city_id")
    private City city;

    // --- Provenance ----------------------------------------------------------

    @Column(name = "rule_code", nullable = false)
    private String ruleCode;

    @Column(name = "metric_name")
    private String metricName;

    @Column(name = "observed_value")
    private BigDecimal observedValue;

    @Column(name = "threshold_value")
    private BigDecimal thresholdValue;

    @Column(name = "zone_metric_window_start")
    private Instant zoneMetricWindowStart;

    @Column(name = "recommended_action")
    private String recommendedAction;

    // --- Lifecycle -----------------------------------------------------------

    @Column(name = "raised_at", nullable = false)
    private Instant raisedAt = Instant.now();

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "acknowledged_by")
    private User acknowledgedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by")
    private User resolvedBy;

    @Column(name = "resolution_note")
    private String resolutionNote;

    /**
     * Suppression identity: rule plus subject plus a coarse time bucket.
     *
     * <p>A partial unique index over open alerts uses this, so a zone that stays
     * congested for an hour produces one alert that stays open rather than one
     * per evaluation. Alert fatigue is how alerting actually fails, and this is
     * the mechanism that prevents it.
     */
    @Column(name = "dedupe_key", nullable = false)
    private String dedupeKey;

    @Column(name = "demo_data", nullable = false)
    private boolean demoData = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public boolean isOpen() {
        return status.isOpen();
    }
}
