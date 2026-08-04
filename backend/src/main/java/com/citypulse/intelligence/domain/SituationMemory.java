package com.citypulse.intelligence.domain;

import com.citypulse.geo.domain.Zone;
import jakarta.persistence.*;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A past situation and what actually followed it (PRD §16).
 *
 * <p>The outcome fields are measurements taken from the two hours after the
 * situation, not predictions. That is the whole value of the memory: asked "has
 * this happened before", it can answer with what happened, and a model's
 * opinion would only be the forecast again.
 */
@Entity
@Table(name = "situation_memory")
@Getter
public class SituationMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uid", nullable = false, unique = true, updatable = false)
    private UUID uid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zone_id", nullable = false)
    private Zone zone;

    @Column(name = "occurred_at", nullable = false)  private Instant occurredAt;

    // The fingerprint, coarse on purpose: matching on exact values would make
    // every situation unique and the memory useless.
    @Column(name = "rain_band", nullable = false)       private String rainBand;
    @Column(name = "day_type", nullable = false)        private String dayType;
    @Column(name = "hour_band", nullable = false)       private String hourBand;
    @Column(name = "had_event", nullable = false)       private boolean hadEvent;
    @Column(name = "incident_band", nullable = false)   private String incidentBand;
    @Column(name = "congestion_band", nullable = false) private String congestionBand;

    @Column(name = "occupancy_at_start") private BigDecimal occupancyAtStart;
    @Column(name = "speed_at_start")     private BigDecimal speedAtStart;
    @Column(name = "risk_at_start")      private BigDecimal riskAtStart;

    @Column(name = "outcome_horizon_minutes", nullable = false) private Integer outcomeHorizonMinutes;
    @Column(name = "peak_occupancy")       private BigDecimal peakOccupancy;
    @Column(name = "min_speed_kph")        private BigDecimal minSpeedKph;
    @Column(name = "peak_risk")            private BigDecimal peakRisk;
    @Column(name = "occupancy_change_pct") private BigDecimal occupancyChangePct;
    @Column(name = "speed_change_pct")     private BigDecimal speedChangePct;
    @Column(name = "risk_change_pct")      private BigDecimal riskChangePct;

    protected SituationMemory() {
        // JPA.
    }
}
