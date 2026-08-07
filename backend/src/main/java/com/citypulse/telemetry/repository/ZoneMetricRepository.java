package com.citypulse.telemetry.repository;

import com.citypulse.telemetry.domain.ZoneMetric;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ZoneMetricRepository extends JpaRepository<ZoneMetric, Long> {

    /**
     * The newest window for every active zone in a city.
     *
     * <p>Written as a lateral join rather than the obvious
     * {@code GROUP BY zone_id HAVING window_start = MAX(...)}: the aggregate form
     * scans every window ever recorded for the city and discards all but the last
     * one, which on a week of five-minute windows is forty thousand rows read to
     * return twenty. The lateral form uses the
     * {@code (zone_id, window_start DESC)} index to fetch exactly one row per
     * zone, so the dashboard's hottest query stays flat as history accumulates
     * (PRD §44).
     *
     * <p>Zones with no telemetry at all are still returned by the outer join in
     * {@code CityLiveService}; this query only supplies the windows that exist.
     */
    @Query(value = """
            SELECT m.*
            FROM zones z
            JOIN LATERAL (
                SELECT *
                FROM zone_metrics zm
                WHERE zm.zone_id = z.id
                  AND zm.window_start >= :notBefore
                ORDER BY zm.window_start DESC
                LIMIT 1
            ) m ON TRUE
            WHERE z.city_id = :cityId
              AND z.active
              AND z.deleted_at IS NULL
            """, nativeQuery = true)
    List<ZoneMetric> findLatestPerZoneInCity(@Param("cityId") Long cityId,
                                             @Param("notBefore") Instant notBefore);

    /** The newest window for a single zone, if it has one inside the staleness bound. */
    @Query(value = """
            SELECT *
            FROM zone_metrics
            WHERE zone_id = :zoneId
              AND window_start >= :notBefore
            ORDER BY window_start DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<ZoneMetric> findLatestForZone(@Param("zoneId") Long zoneId,
                                           @Param("notBefore") Instant notBefore);

    /**
     * A zone's recent history, oldest first, for sparklines and trend panels.
     */
    @Query("""
            SELECT m FROM ZoneMetric m
            WHERE m.zoneId = :zoneId
              AND m.windowStart >= :from
              AND m.windowStart < :to
            ORDER BY m.windowStart ASC
            """)
    List<ZoneMetric> findWindow(@Param("zoneId") Long zoneId,
                                @Param("from") Instant from,
                                @Param("to") Instant to,
                                Pageable pageable);

    /**
     * A city's history, one row per window, aggregated across its zones.
     *
     * <p>Aggregated in SQL rather than by fetching every zone's windows and
     * folding them in Java: six hours of a twenty-zone city is 1,440 rows to
     * transfer and discard, and the same figures the database can produce in one
     * pass.
     *
     * <p>The averages ignore nulls, which is what AVG does and what is wanted —
     * a zone that reported traffic but no air quality should not drag the city's
     * AQI toward zero. {@code reportingZones} counts the zones that contributed,
     * so a caller can tell an average over three zones from one over twenty.
     */
    @Query("""
            SELECT m.windowStart                AS windowStart,
                   AVG(m.occupancyRatio)        AS occupancyRatio,
                   AVG(m.averageSpeedKph)       AS averageSpeedKph,
                   AVG(m.aqi)                   AS aqi,
                   AVG(m.riskScore)             AS riskScore,
                   SUM(m.vehicleCount)          AS vehicleCount,
                   SUM(m.activeIncidents)       AS activeIncidents,
                   COUNT(m.id)                  AS reportingZones
            FROM ZoneMetric m
            WHERE m.zoneId IN :zoneIds
              AND m.windowStart >= :from
              AND m.windowStart < :to
            GROUP BY m.windowStart
            ORDER BY m.windowStart ASC
            """)
    List<CityHistoryRow> findCityWindow(@Param("zoneIds") List<Long> zoneIds,
                                        @Param("from") Instant from,
                                        @Param("to") Instant to,
                                        Pageable pageable);

    /** Projection for {@link #findCityWindow}. */
    interface CityHistoryRow {
        Instant getWindowStart();
        BigDecimal getOccupancyRatio();
        BigDecimal getAverageSpeedKph();
        Double getAqi();
        BigDecimal getRiskScore();
        Long getVehicleCount();
        Long getActiveIncidents();
        Long getReportingZones();
    }

    /**
     * The newest window boundary anywhere in a city.
     *
     * <p>Drives the "as of" stamp and the staleness warning. Without it the UI
     * would have no honest way to say how current the numbers are, and a stalled
     * pipeline would look identical to a quiet city.
     */
    @Query(value = """
            SELECT max(zm.window_start)
            FROM zone_metrics zm
            JOIN zones z ON z.id = zm.zone_id
            WHERE z.city_id = :cityId AND z.deleted_at IS NULL
            """, nativeQuery = true)
    Optional<Instant> findNewestWindowStart(@Param("cityId") Long cityId);
}
