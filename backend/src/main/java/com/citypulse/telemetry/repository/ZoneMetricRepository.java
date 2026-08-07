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

    /**
     * The same aggregate, folded into fixed-width buckets.
     *
     * <p>Curated windows are five minutes wide, so a month is 8,640 of them
     * against a 500-window cap. Returning the first 500 would have answered "the
     * last 30 days" with the first 42 hours of it, labelled as a month — a
     * silent truncation, which is worse than a refusal because nothing about the
     * chart would look wrong.
     *
     * <p>Native rather than JPQL: bucketing needs epoch arithmetic that JPQL has
     * no way to express. The averages are of the per-zone rows in the bucket,
     * which is the mean over zone-windows rather than a mean of window means —
     * they differ only when zones report unevenly, and the zone-window mean is
     * the one that weights an hour by how much was actually measured in it.
     */
    @Query(value = """
            SELECT to_timestamp(floor(extract(epoch FROM window_start) / :seconds) * :seconds)
                                              AS window_start,
                   AVG(occupancy_ratio)       AS occupancy_ratio,
                   AVG(average_speed_kph)     AS average_speed_kph,
                   AVG(aqi)                   AS aqi,
                   AVG(risk_score)            AS risk_score,
                   SUM(vehicle_count)         AS vehicle_count,
                   SUM(active_incidents)      AS active_incidents,
                   COUNT(DISTINCT zone_id)    AS reporting_zones
            FROM zone_metrics
            WHERE zone_id IN :zoneIds
              AND window_start >= :from
              AND window_start < :to
            GROUP BY 1
            ORDER BY 1 ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<CityHistoryRow> findCityBuckets(@Param("zoneIds") List<Long> zoneIds,
                                         @Param("from") Instant from,
                                         @Param("to") Instant to,
                                         @Param("seconds") long seconds,
                                         @Param("limit") int limit);

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
