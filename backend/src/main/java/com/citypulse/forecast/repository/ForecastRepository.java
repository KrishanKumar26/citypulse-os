package com.citypulse.forecast.repository;

import com.citypulse.forecast.domain.Forecast;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ForecastRepository extends JpaRepository<Forecast, Long> {

    /**
     * The newest forecast per horizon for one zone and metric.
     *
     * <p>A lateral join rather than a group-by: predictions accumulate every
     * cycle, so the aggregate form would scan every forecast ever issued for
     * this zone to return five rows. The index on
     * {@code (zone_id, target_metric, issued_at DESC)} makes this read exactly
     * one row per horizon.
     */
    @Query(value = """
            SELECT f.*
            FROM (SELECT DISTINCT horizon_minutes FROM forecasts WHERE zone_id = :zoneId) h
            JOIN LATERAL (
                SELECT *
                FROM forecasts
                WHERE zone_id = :zoneId
                  AND target_metric = :metric
                  AND horizon_minutes = h.horizon_minutes
                ORDER BY issued_at DESC
                LIMIT 1
            ) f ON TRUE
            ORDER BY f.horizon_minutes
            """, nativeQuery = true)
    List<Forecast> findLatestPerHorizon(@Param("zoneId") Long zoneId,
                                        @Param("metric") String metric);

    /**
     * The newest forecast at one horizon for every zone in a city.
     *
     * <p>Drives the city-wide forecast map: what the whole city is expected to
     * look like an hour from now.
     */
    @Query(value = """
            SELECT f.*
            FROM zones z
            JOIN LATERAL (
                SELECT *
                FROM forecasts
                WHERE zone_id = z.id
                  AND target_metric = :metric
                  AND horizon_minutes = :horizon
                ORDER BY issued_at DESC
                LIMIT 1
            ) f ON TRUE
            WHERE z.city_id = :cityId AND z.active AND z.deleted_at IS NULL
            ORDER BY f.predicted_value DESC
            """, nativeQuery = true)
    List<Forecast> findLatestForCityAtHorizon(@Param("cityId") Long cityId,
                                              @Param("metric") String metric,
                                              @Param("horizon") int horizonMinutes);
}
