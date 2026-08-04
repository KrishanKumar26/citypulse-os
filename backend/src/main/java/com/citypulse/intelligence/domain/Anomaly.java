package com.citypulse.intelligence.domain;

import com.citypulse.geo.domain.City;
import com.citypulse.geo.domain.Zone;
import jakarta.persistence.*;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A departure from what a zone normally does at this hour (PRD §13).
 *
 * <p>Distinct from an {@code Alert}, which fires on a fixed threshold. 8,000
 * vehicles is unremarkable on a Tuesday morning and a genuine anomaly at 3 a.m.;
 * a threshold cannot express that and a learned baseline can.
 *
 * <p>Every row carries the observation, the baseline it was judged against and
 * the gap between them, because that triple *is* the explanation. An anomaly
 * that cannot be stated as "17,800 against a normal of 8,000" is one the user
 * has to take on faith.
 */
@Entity
@Table(name = "anomalies")
@Getter
public class Anomaly {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uid", nullable = false, unique = true, updatable = false)
    private UUID uid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zone_id", nullable = false)
    private Zone zone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "city_id", nullable = false)
    private City city;

    @Column(name = "metric", nullable = false)          private String metric;
    @Column(name = "anomaly_type", nullable = false)    private String anomalyType;
    @Column(name = "severity", nullable = false)        private String severity;
    @Column(name = "window_start", nullable = false)    private Instant windowStart;

    @Column(name = "observed_value", nullable = false)  private BigDecimal observedValue;
    @Column(name = "baseline_value", nullable = false)  private BigDecimal baselineValue;
    @Column(name = "baseline_mad", nullable = false)    private BigDecimal baselineMad;
    @Column(name = "deviation_score", nullable = false) private BigDecimal deviationScore;
    @Column(name = "percent_change")                    private BigDecimal percentChange;

    /** How many historical windows the baseline rests on. Thin baselines earn less trust. */
    @Column(name = "baseline_samples", nullable = false) private Integer baselineSamples;

    /** Written at detection time so it stays true after the code changes. */
    @Column(name = "explanation", nullable = false)     private String explanation;

    @Column(name = "detected_at", nullable = false)     private Instant detectedAt;
    @Column(name = "demo_data", nullable = false)       private boolean demoData;

    protected Anomaly() {
        // JPA.
    }
}
