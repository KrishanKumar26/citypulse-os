package com.citypulse.telemetry.service;

import com.citypulse.common.time.Timestamps;
import com.citypulse.telemetry.domain.DataSource;
import com.citypulse.telemetry.dto.DataSourceResponses;
import com.citypulse.telemetry.repository.DataSourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What the platform is ingesting, and whether it actually is.
 *
 * <p>The distinction this exists for: a source's row says ACTIVE and carries a
 * {@code last_ingested_at}, and neither is evidence. The status is a
 * configuration, and the timestamp is maintained by whatever writes the events —
 * a retry can touch it without a single row arriving. So the volume is counted
 * from the event tables, and a source that claims to be running while having
 * delivered nothing is flagged rather than listed alongside the healthy ones.
 */
@Service
public class DataSourceService {

    /**
     * How far back the row counts look.
     *
     * <p>Wide enough that the hosted deployment's hourly refresh does not make
     * every source look silent between runs, narrow enough that a feed which
     * stopped this morning does not still read as healthy.
     */
    private static final int WINDOW_HOURS = 6;

    private final DataSourceRepository repository;

    public DataSourceService(DataSourceRepository repository) {
        this.repository = repository;
    }

    /**
     * Whether the pipeline behind the numbers is healthy.
     *
     * <p>Distinct from {@link #list()}, which answers "is each feed delivering".
     * This answers "is what arrived any good" — received against kept, rejected,
     * duplicated, late, and how far behind the worst record was.
     *
     * <p>Only stages the pipeline writes metrics for appear. A stage that is
     * absent is uninstrumented, not idle, and the response says so rather than
     * letting a reader infer a clean bill of health from an empty row.
     */
    @Transactional(readOnly = true)
    public DataSourceResponses.PipelineHealth health() {
        Instant since = Timestamps.now().minus(Duration.ofHours(WINDOW_HOURS));

        List<DataSourceResponses.StageQuality> stages = repository.qualityByStage(since).stream()
                .map(row -> {
                    long received = row.getReceived() == null ? 0 : row.getReceived();
                    long valid = row.getValid() == null ? 0 : row.getValid();
                    return new DataSourceResponses.StageQuality(
                            row.getStage(),
                            row.getWindows() == null ? 0 : row.getWindows(),
                            received, valid,
                            row.getRejected() == null ? 0 : row.getRejected(),
                            row.getDuplicates() == null ? 0 : row.getDuplicates(),
                            row.getLate() == null ? 0 : row.getLate(),
                            // Null, not zero, when nothing arrived. A ratio over an
                            // empty denominator is undefined, and rendering it as
                            // 0% would report a quiet hour as a broken pipeline.
                            received == 0
                                    ? null
                                    : BigDecimal.valueOf(valid)
                                            .divide(BigDecimal.valueOf(received), 4, RoundingMode.HALF_UP),
                            row.getMaxLagSeconds(),
                            row.getNewestWindowEnd());
                })
                .toList();

        DataSourceResponses.SourceList sources = list();
        return new DataSourceResponses.PipelineHealth(
                WINDOW_HOURS,
                stages,
                repository.deadLetteredSince(since),
                sources.silent(),
                sources.total());
    }

    @Transactional(readOnly = true)
    public DataSourceResponses.SourceList list() {
        Instant since = Timestamps.now().minus(Duration.ofHours(WINDOW_HOURS));
        List<DataSource> sources = repository.findAllActive();

        Map<Long, Long> volumes = repository.countRowsSince(since).stream()
                .filter(v -> v.getSourceId() != null)
                .collect(Collectors.toMap(
                        DataSourceRepository.SourceVolume::getSourceId,
                        v -> v.getRows() == null ? 0L : v.getRows(),
                        Long::sum));

        Instant now = Timestamps.now();
        List<DataSourceResponses.Source> mapped = sources.stream()
                .map(source -> {
                    long rows = volumes.getOrDefault(source.getId(), 0L);
                    boolean active = "ACTIVE".equals(source.getStatus());
                    return new DataSourceResponses.Source(
                            source.getUid().toString(),
                            source.getCode(),
                            source.getName(),
                            source.getDescription(),
                            source.getSourceType(),
                            source.getIngestionMode(),
                            source.getStatus(),
                            source.getLastIngestedAt(),
                            source.getLastIngestedAt() == null
                                    ? null
                                    : Duration.between(source.getLastIngestedAt(), now).toSeconds(),
                            rows,
                            // Configured to run, and not running. A paused source
                            // delivering nothing is expected; an ACTIVE one
                            // delivering nothing is the thing worth surfacing.
                            active && rows == 0,
                            source.isDemoData());
                })
                .toList();

        return new DataSourceResponses.SourceList(
                WINDOW_HOURS,
                mapped.size(),
                (int) mapped.stream().filter(s -> "ACTIVE".equals(s.status())).count(),
                (int) mapped.stream().filter(DataSourceResponses.Source::silent).count(),
                mapped);
    }

}
