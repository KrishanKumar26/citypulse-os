package com.citypulse.forecast.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Measured error for one (target metric, horizon) pair.
 *
 * <p>This is the row the confidence on every forecast was derived from, which is
 * why the API exposes it alongside predictions: a caller who wants to know
 * whether to trust a number can read the error that produced its confidence,
 * and the persistence baseline it was measured against.
 *
 * <p>Separate from {@link ModelRun} because a run is evaluated across five
 * horizons, and its 15-minute error is not its 6-hour error. One number per run
 * would let a model that is excellent at 15 minutes lend unearned confidence to
 * its 6-hour predictions.
 */
@Entity
@Table(name = "model_metrics")
@Getter
public class ModelMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "model_run_id", nullable = false)
    private Long modelRunId;

    @Column(name = "target_metric", nullable = false)
    private String targetMetric;

    @Column(name = "horizon_minutes", nullable = false)
    private Integer horizonMinutes;

    @Column(name = "mae", nullable = false)
    private BigDecimal mae;

    /** Null where actuals were too near zero for a percentage to mean anything. */
    @Column(name = "mape")
    private BigDecimal mape;

    @Column(name = "rmse")
    private BigDecimal rmse;

    /**
     * Error of the naive "nothing changes" prediction.
     *
     * <p>Reported because a model that cannot beat persistence has not earned
     * its complexity, and an MAE without this comparison lets a useless model
     * look precise.
     */
    @Column(name = "baseline_mae")
    private BigDecimal baselineMae;

    @Column(name = "sample_count", nullable = false)
    private Integer sampleCount;

    protected ModelMetric() {
        // JPA.
    }

    /** How much better than doing nothing, as a percentage. Null if unmeasured. */
    public BigDecimal improvementOverBaseline() {
        if (baselineMae == null || baselineMae.signum() == 0) {
            return null;
        }
        return BigDecimal.ONE.subtract(mae.divide(baselineMae, 6, java.math.RoundingMode.HALF_UP))
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, java.math.RoundingMode.HALF_UP);
    }
}
