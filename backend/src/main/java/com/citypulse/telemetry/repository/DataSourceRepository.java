package com.citypulse.telemetry.repository;

import com.citypulse.telemetry.domain.DataSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface DataSourceRepository extends JpaRepository<DataSource, Long> {

    @Query("""
            SELECT s FROM DataSource s
            WHERE s.deletedAt IS NULL
            ORDER BY s.sourceType ASC, s.name ASC
            """)
    List<DataSource> findAllActive();

    /**
     * Rows each source actually produced in a window, counted from the event
     * tables rather than trusted from {@code last_ingested_at}.
     *
     * <p>The timestamp column is maintained by whatever writes the events. A
     * source that stopped writing but whose timestamp was last touched by a
     * retry would read as healthy; counting the rows cannot lie in that
     * direction. Where the two disagree, the count is the measurement and the
     * timestamp is a claim.
     */
    @Query(value = """
            SELECT source_id AS sourceId, count(*) AS rows
            FROM (
                -- ingested_at, not the event's own timestamp. The question is
                -- "did this feed deliver recently", and a backfill of last
                -- week's data is a delivery now. Judging by event_time would
                -- report a working backfill as a dead source.
                SELECT source_id FROM traffic_events     WHERE ingested_at >= :since
                UNION ALL
                SELECT source_id FROM weather_events     WHERE ingested_at >= :since
                UNION ALL
                SELECT source_id FROM air_quality_events WHERE ingested_at >= :since
                UNION ALL
                SELECT source_id FROM incidents          WHERE ingested_at >= :since
                UNION ALL
                SELECT source_id FROM city_events        WHERE ingested_at >= :since
            ) e
            GROUP BY source_id
            """, nativeQuery = true)
    List<SourceVolume> countRowsSince(java.time.Instant since);

    interface SourceVolume {
        Long getSourceId();
        Long getRows();
    }
}
