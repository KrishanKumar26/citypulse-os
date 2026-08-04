package com.citypulse.forecast.repository;

import com.citypulse.forecast.domain.ModelMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ModelMetricRepository extends JpaRepository<ModelMetric, Long> {

    List<ModelMetric> findByModelRunIdOrderByTargetMetricAscHorizonMinutesAsc(Long modelRunId);

    /**
     * Production error next to the error measured on the holdout.
     *
     * <p>The comparison is the point. Holdout error says how the model did on
     * data it had not seen *then*; production error says how it is doing *now*.
     * A widening gap is the signal that the model has gone stale, and neither
     * number alone can show it.
     */
    @Query(value = """
            SELECT fa.target_metric        AS "targetMetric",
                   fa.horizon_minutes      AS "horizonMinutes",
                   count(*)                AS "scoredCount",
                   round(avg(fa.absolute_error), 4) AS "productionMae",
                   mm.mae                  AS "holdoutMae",
                   round(100.0 * count(*) FILTER (WHERE fa.within_bounds) / count(*), 1)
                                           AS "withinIntervalPct"
            FROM forecast_accuracy fa
            JOIN model_metrics mm
              ON mm.model_run_id = fa.model_run_id
             AND mm.target_metric = fa.target_metric
             AND mm.horizon_minutes = fa.horizon_minutes
            WHERE fa.model_run_id = :runId
            GROUP BY fa.target_metric, fa.horizon_minutes, mm.mae
            ORDER BY fa.target_metric, fa.horizon_minutes
            """, nativeQuery = true)
    List<AccuracyRow> findAccuracyForRun(@Param("runId") Long runId);

    /** Projection for the accuracy comparison above. */
    interface AccuracyRow {
        String getTargetMetric();
        Integer getHorizonMinutes();
        Long getScoredCount();
        java.math.BigDecimal getProductionMae();
        java.math.BigDecimal getHoldoutMae();
        java.math.BigDecimal getWithinIntervalPct();
    }
}
