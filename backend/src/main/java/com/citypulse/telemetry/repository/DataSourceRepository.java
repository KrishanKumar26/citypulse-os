package com.citypulse.telemetry.repository;

import com.citypulse.telemetry.domain.DataSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
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

    /**
     * Pipeline quality per stage over a window.
     *
     * <p>Written by the loader, which is the only thing that can count what it
     * received against what it kept. A validity ratio computed after the fact
     * from the curated tables cannot see a record that was rejected, so it would
     * always read 100%.
     */
    @Query(value = """
            SELECT stage                          AS stage,
                   count(*)                       AS windows,
                   coalesce(sum(records_received), 0)  AS received,
                   coalesce(sum(records_valid), 0)     AS valid,
                   coalesce(sum(records_rejected), 0)  AS rejected,
                   coalesce(sum(records_duplicate), 0) AS duplicates,
                   coalesce(sum(records_late), 0)      AS late,
                   max(max_lag_seconds)           AS maxLagSeconds,
                   max(window_end)                AS newestWindowEnd
            FROM data_quality_metrics
            WHERE window_end >= :since
            GROUP BY stage
            ORDER BY stage
            """, nativeQuery = true)
    List<StageQuality> qualityByStage(@Param("since") Instant since);

    interface StageQuality {
        String getStage();
        Long getWindows();
        Long getReceived();
        Long getValid();
        Long getRejected();
        Long getDuplicates();
        Long getLate();
        Long getMaxLagSeconds();
        Instant getNewestWindowEnd();
    }

    /** Records the pipeline refused, over the same window. */
    @Query(value = "SELECT count(*) FROM ingestion_dlq WHERE rejected_at >= :since", nativeQuery = true)
    long deadLetteredSince(@Param("since") Instant since);

    interface SourceVolume {
        Long getSourceId();
        Long getRows();
    }
}
