package com.citypulse.intervention.repository;

import com.citypulse.intervention.domain.Intervention;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InterventionRepository extends JpaRepository<Intervention, Long> {

    Optional<Intervention> findByUidAndDeletedAtIsNull(UUID uid);

    @Query("""
            SELECT i FROM Intervention i
            WHERE i.city.id = :cityId AND i.deletedAt IS NULL
            ORDER BY i.startedAt DESC
            """)
    List<Intervention> findForCity(@Param("cityId") Long cityId);

    /**
     * A zone's mean readings over a window.
     *
     * <p>Used twice per intervention — once before the stated start, once after
     * — and the two are what "impact" is measured from.
     *
     * <p>Returns nulls rather than zeroes when a window held no readings. A
     * period with no telemetry must not be averaged as calm; it has to be
     * reportable as absent, or an intervention during an outage would score as
     * a triumph.
     */
    @Query(value = """
            SELECT AVG(occupancy_ratio)   AS occupancy,
                   AVG(average_speed_kph) AS speed,
                   AVG(risk_score)        AS risk,
                   COUNT(*)               AS windows
            FROM zone_metrics
            WHERE zone_id = :zoneId
              AND window_start >= :from
              AND window_start < :to
            """, nativeQuery = true)
    WindowMean meanForZone(@Param("zoneId") Long zoneId,
                           @Param("from") Instant from,
                           @Param("to") Instant to);

    interface WindowMean {
        BigDecimal getOccupancy();
        BigDecimal getSpeed();
        BigDecimal getRisk();
        Long getWindows();
    }

    /**
     * What this zone normally does across the hours a window spans.
     *
     * <p>The comparison that makes the measurement mean anything. Congestion
     * falls in the evening whether or not anyone intervened, so a raw
     * before/after difference credits the action with the sunset. These are the
     * same medians the anomaly detector uses, keyed by hour of week.
     */
    @Query(value = """
            SELECT metric AS metric, AVG(median_value) AS median, SUM(sample_count) AS samples
            FROM zone_baselines
            WHERE zone_id = :zoneId AND hour_of_week IN (:hours)
            GROUP BY metric
            """, nativeQuery = true)
    List<BaselineMean> baselineForHours(@Param("zoneId") Long zoneId,
                                        @Param("hours") List<Integer> hours);

    interface BaselineMean {
        String getMetric();
        BigDecimal getMedian();
        Long getSamples();
    }
}
