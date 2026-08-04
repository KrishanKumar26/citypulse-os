package com.citypulse.forecast.domain;

import com.citypulse.geo.domain.Zone;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One stored prediction (PRD §11).
 *
 * <p>Read-only from the backend, like {@code ZoneMetric}: the Python training
 * and prediction jobs own these rows. The backend never runs a model — it serves
 * predictions that were produced deliberately and can therefore be scored later
 * against what actually happened.
 *
 * <p>{@code confidence} is stored rather than computed on read because it was
 * derived from the measured holdout error of the run that produced this
 * forecast. Recomputing it here would mean the backend deciding how confident to
 * look, which is exactly what PRD §11's exit criterion rules out.
 */
@Entity
@Table(name = "forecasts")
@Getter
public class Forecast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uid", nullable = false, unique = true, updatable = false)
    private UUID uid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zone_id", nullable = false)
    private Zone zone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "model_run_id", nullable = false)
    private ModelRun modelRun;

    @Column(name = "target_metric", nullable = false)
    private String targetMetric;

    @Column(name = "horizon_minutes", nullable = false)
    private Integer horizonMinutes;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "target_time", nullable = false)
    private Instant targetTime;

    /** The last observed window this prediction was based on. */
    @Column(name = "based_on_window", nullable = false)
    private Instant basedOnWindow;

    @Column(name = "predicted_value", nullable = false)
    private BigDecimal predictedValue;

    @Column(name = "lower_bound")
    private BigDecimal lowerBound;

    @Column(name = "upper_bound")
    private BigDecimal upperBound;

    @Column(name = "confidence", nullable = false)
    private BigDecimal confidence;

    @Column(name = "risk_level")
    private String riskLevel;

    /**
     * The features that moved this prediction furthest from the average.
     *
     * <p>Stored as written rather than recomputed, so a forecast can still be
     * explained after the feature code has moved on — an explanation that
     * changes when the code changes is not an explanation of *this* prediction.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "contributing_factors", nullable = false)
    private String contributingFactors;

    @Column(name = "demo_data", nullable = false)
    private boolean demoData;

    protected Forecast() {
        // JPA.
    }
}
