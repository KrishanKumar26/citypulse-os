package com.citypulse.telemetry.service;

import com.citypulse.alert.repository.AlertRepository;
import com.citypulse.common.exception.Exceptions;
import com.citypulse.geo.domain.City;
import com.citypulse.geo.domain.Zone;
import com.citypulse.geo.repository.CityRepository;
import com.citypulse.geo.repository.ZoneRepository;
import com.citypulse.telemetry.config.TelemetryProperties;
import com.citypulse.telemetry.domain.ConditionLevel;
import com.citypulse.telemetry.domain.ZoneMetric;
import com.citypulse.telemetry.dto.TelemetryResponses;
import com.citypulse.telemetry.repository.ZoneMetricRepository;
import com.citypulse.user.domain.Permissions;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reads curated conditions and assembles what the Command Center renders
 * (PRD §8, §9).
 *
 * <p>This service computes nothing that the pipeline already computed. Risk
 * scores, congestion bands and AQI categories are read from
 * {@code zone_metrics} as stored. Recomputing them here would create a second
 * definition of "high congestion" that could disagree with the one the data was
 * written under, and the two would drift the first time either changed.
 *
 * <p>What it does do is aggregate across zones, which is genuinely a read-time
 * concern: the pipeline has no reason to know a city's average, and materialising
 * one per window would be a second thing to keep correct.
 */
@Service
public class LiveMetricsService {

    private final CityRepository cityRepository;
    private final ZoneRepository zoneRepository;
    private final ZoneMetricRepository metricRepository;
    private final AlertRepository alertRepository;
    private final TelemetryProperties properties;

    public LiveMetricsService(CityRepository cityRepository,
                              ZoneRepository zoneRepository,
                              ZoneMetricRepository metricRepository,
                              AlertRepository alertRepository,
                              TelemetryProperties properties) {
        this.cityRepository = cityRepository;
        this.zoneRepository = zoneRepository;
        this.metricRepository = metricRepository;
        this.alertRepository = alertRepository;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.TELEMETRY_READ + "')")
    public TelemetryResponses.CitySnapshot snapshotByCityId(UUID cityUid) {
        City city = cityRepository.findByUidAndDeletedAtIsNull(cityUid)
                .orElseThrow(() -> new Exceptions.NotFound("City", cityUid));
        return snapshot(city);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.TELEMETRY_READ + "')")
    public TelemetryResponses.CitySnapshot snapshotBySlug(String slug) {
        City city = cityRepository.findBySlugAndDeletedAtIsNull(slug)
                .orElseThrow(() -> new Exceptions.NotFound("City", slug));
        return snapshot(city);
    }

    /**
     * A snapshot for a caller whose authorisation was already established
     * elsewhere.
     *
     * <p>Deliberately not annotated with {@code @PreAuthorize}, and deliberately
     * not exposed to controllers. Two callers need it:
     *
     * <ul>
     *   <li>the SSE subscription path, where the caller proved entitlement by
     *       redeeming a ticket — the JWT filter never runs for that request, so
     *       there is no {@code Authentication} for a method-level check to
     *       inspect;</li>
     *   <li>the scheduled push loop, which runs on a scheduler thread with no
     *       user in context at all and is pushing to subscribers who were each
     *       authorised at subscribe time.</li>
     * </ul>
     *
     * <p>Re-checking an authority that no longer exists on the thread would fail
     * every push. The alternative — inventing a system principal and running the
     * scheduler as it — would mean a permanent authenticated context sitting in
     * the application for the convenience of one loop, which is a larger hole
     * than this method.
     */
    @Transactional(readOnly = true)
    public TelemetryResponses.CitySnapshot snapshotForAuthorisedSubscriber(String slug) {
        City city = cityRepository.findBySlugAndDeletedAtIsNull(slug)
                .orElseThrow(() -> new Exceptions.NotFound("City", slug));
        return snapshot(city);
    }

    /**
     * Builds one consistent view of a city.
     *
     * <p>Zones are listed whether or not they have telemetry. A zone that is
     * monitored but silent is information — it means a feed has stopped — and
     * dropping it from the response would make the map quietly shrink instead.
     */
    TelemetryResponses.CitySnapshot snapshot(City city) {
        Instant now = Instant.now();
        Instant notBefore = now.minus(properties.maxAge());

        List<Zone> zones = zoneRepository.findByCity(city.getId(), true);
        Map<Long, ZoneMetric> latest = metricRepository
                .findLatestPerZoneInCity(city.getId(), notBefore)
                .stream()
                // A zone cannot appear twice; the lateral join returns one row each.
                .collect(Collectors.toMap(ZoneMetric::getZoneId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));

        // One query for the whole city's prior readings, not one per zone: twenty
        // zones would otherwise be twenty round trips to put an arrow on a table.
        Map<Long, ZoneMetricRepository.PriorReading> prior = zones.isEmpty()
                ? Map.of()
                : metricRepository.findPriorReadings(
                        zones.stream().map(Zone::getId).toList(),
                        now.minus(TREND_LOOKBACK),
                        now.minus(TREND_MAX_AGE))
                .stream()
                .collect(Collectors.toMap(
                        ZoneMetricRepository.PriorReading::getZoneId, Function.identity(),
                        (a, b) -> a));

        List<TelemetryResponses.ZoneCondition> conditions = zones.stream()
                .map(zone -> toCondition(zone, latest.get(zone.getId()), prior.get(zone.getId())))
                .sorted(Comparator.comparing(TelemetryResponses.ZoneCondition::zoneCode))
                .toList();

        Instant asOf = metricRepository.findNewestWindowStart(city.getId()).orElse(null);
        Long ageSeconds = asOf == null ? null : Math.max(0, now.getEpochSecond() - asOf.getEpochSecond());
        // No data at all is treated as stale: a dashboard that has never received
        // anything must not present itself as current.
        boolean stale = asOf == null || ageSeconds > properties.freshnessBudget().toSeconds();

        int openAlerts = alertRepository.countOpenForCity(city.getId());

        return new TelemetryResponses.CitySnapshot(
                city.getUid().toString(),
                city.getSlug(),
                city.getName(),
                city.getTimezone(),
                asOf,
                ageSeconds,
                stale,
                aggregate(conditions, openAlerts),
                conditions,
                city.isDemoData()
        );
    }

    /**
     * How far back the trend compares to.
     *
     * <p>An hour is long enough that five-minute noise does not read as a move,
     * and short enough that the comparison is still about now.
     */
    private static final Duration TREND_LOOKBACK = Duration.ofHours(1);

    /**
     * How far past the lookback a prior window may be and still count.
     *
     * <p>Without a floor, a zone whose feed stopped yesterday would be compared
     * against yesterday and the arrow would describe a day, not an hour, while
     * looking identical to one that does. Nothing older than this produces no
     * trend at all, which the client renders as "no trend yet" rather than as
     * steady.
     */
    private static final Duration TREND_MAX_AGE = Duration.ofHours(3);

    private TelemetryResponses.ZoneCondition toCondition(Zone zone, ZoneMetric metric) {
        return toCondition(zone, metric, null);
    }

    private TelemetryResponses.ZoneCondition toCondition(
            Zone zone, ZoneMetric metric, ZoneMetricRepository.PriorReading prior) {
        if (metric == null) {
            // Every metric null, hasData false. The client renders "no data"
            // rather than a zero-valued tile.
            return new TelemetryResponses.ZoneCondition(
                    zone.getUid().toString(), zone.getCode(), zone.getName(),
                    zone.getZoneType().name(),
                    zone.getCenterLatitude(), zone.getCenterLongitude(),
                    null, null,
                    null, null, null, null,
                    null, null, null, null,
                    null, null, null,
                    0, 0,
                    null, null,
                    null, null,
                    0, false, false
            );
        }

        return new TelemetryResponses.ZoneCondition(
                zone.getUid().toString(), zone.getCode(), zone.getName(),
                zone.getZoneType().name(),
                zone.getCenterLatitude(), zone.getCenterLongitude(),
                metric.getWindowStart(), metric.getWindowEnd(),
                metric.getVehicleCount(), metric.getAverageSpeedKph(), metric.getOccupancyRatio(),
                metric.getCongestionLevel(),
                metric.getAqi(), metric.getAqiCategory(), metric.getAqiSource(),
                metric.getTemperatureC(), metric.getPrecipitationMmH(), metric.getWeatherCondition(),
                metric.getWeatherSource(),
                metric.getActiveIncidents() == null ? 0 : metric.getActiveIncidents(),
                metric.getActiveEvents() == null ? 0 : metric.getActiveEvents(),
                metric.getRiskScore(), metric.getRiskLevel(),
                prior == null ? null : prior.getRiskScore(),
                prior == null ? null : prior.getWindowStart(),
                metric.getSampleCount(), metric.isDemoData(), true
        );
    }

    /**
     * Rolls per-zone conditions into the KPI row.
     *
     * <p>Averages are taken only over zones that actually reported. Including
     * silent zones as zeroes would drag every city average toward zero as feeds
     * dropped out — the dashboard would look calmer precisely as the platform
     * lost visibility, which is the most dangerous direction for it to be wrong.
     */
    private TelemetryResponses.CityKpis aggregate(List<TelemetryResponses.ZoneCondition> zones,
                                                  int openAlerts) {
        List<TelemetryResponses.ZoneCondition> reporting = zones.stream()
                .filter(TelemetryResponses.ZoneCondition::hasData)
                .toList();

        int degraded = (int) reporting.stream()
                .map(z -> ConditionLevel.fromNullable(z.riskLevel()))
                .filter(level -> level != null && level.isActionable())
                .count();

        BigDecimal avgRisk = average(reporting, TelemetryResponses.ZoneCondition::riskScore, 2);

        return new TelemetryResponses.CityKpis(
                average(reporting, TelemetryResponses.ZoneCondition::occupancyRatio, 4),
                average(reporting, TelemetryResponses.ZoneCondition::averageSpeedKph, 2),
                sumLong(reporting),
                averageInt(reporting),
                average(reporting, TelemetryResponses.ZoneCondition::temperatureC, 2),
                average(reporting, TelemetryResponses.ZoneCondition::precipitationMmH, 2),
                // Weather is a city-level reading duplicated onto each zone, so any
                // reporting zone carries the same value; the first is representative.
                reporting.stream()
                        .map(TelemetryResponses.ZoneCondition::weatherCondition)
                        .filter(java.util.Objects::nonNull)
                        .findFirst().orElse(null),
                // Null rather than 0 when nothing reported — see CityKpis. The
                // averages above already exclude silent zones for the same
                // reason; these two were summing over an empty list and
                // presenting the result as a measurement.
                reporting.isEmpty() ? null
                        : reporting.stream().mapToInt(TelemetryResponses.ZoneCondition::activeIncidents).sum(),
                reporting.isEmpty() ? null
                        : reporting.stream().mapToInt(TelemetryResponses.ZoneCondition::activeEvents).sum(),
                openAlerts,
                avgRisk,
                bandOf(avgRisk),
                reporting.size(),
                zones.size(),
                degraded
        );
    }

    /**
     * Bands a city-average risk score onto the same four-state scale.
     *
     * <p>Thresholds match {@code common/transforms.py}'s {@code risk_level}. They
     * are duplicated rather than shared because the two live in different
     * languages; {@code RiskBandTest} asserts they still agree.
     */
    static String bandOf(BigDecimal score) {
        if (score == null) {
            return null;
        }
        double value = score.doubleValue();
        if (value <= 25.0) return ConditionLevel.NORMAL.name();
        if (value <= 50.0) return ConditionLevel.MODERATE.name();
        if (value <= 75.0) return ConditionLevel.HIGH.name();
        return ConditionLevel.CRITICAL.name();
    }

    private BigDecimal average(List<TelemetryResponses.ZoneCondition> zones,
                               Function<TelemetryResponses.ZoneCondition, BigDecimal> field,
                               int scale) {
        List<BigDecimal> values = zones.stream().map(field)
                .filter(java.util.Objects::nonNull).toList();
        if (values.isEmpty()) {
            return null;
        }
        BigDecimal total = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(values.size()), scale, RoundingMode.HALF_UP);
    }

    private Integer averageInt(List<TelemetryResponses.ZoneCondition> zones) {
        List<Integer> values = zones.stream().map(TelemetryResponses.ZoneCondition::aqi)
                .filter(java.util.Objects::nonNull).toList();
        if (values.isEmpty()) {
            return null;
        }
        return (int) Math.round(values.stream().mapToInt(Integer::intValue).average().orElse(0));
    }

    private Long sumLong(List<TelemetryResponses.ZoneCondition> zones) {
        List<Integer> values = zones.stream().map(TelemetryResponses.ZoneCondition::vehicleCount)
                .filter(java.util.Objects::nonNull).toList();
        return values.isEmpty() ? null : values.stream().mapToLong(Integer::longValue).sum();
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.TELEMETRY_READ + "')")
    public TelemetryResponses.ZoneHistory history(UUID zoneUid, Instant from, Instant to) {
        Zone zone = zoneRepository.findByUidAndDeletedAtIsNull(zoneUid)
                .orElseThrow(() -> new Exceptions.NotFound("Zone", zoneUid));

        Instant end = Optional.ofNullable(to).orElseGet(Instant::now);
        Instant start = Optional.ofNullable(from).orElseGet(() -> end.minus(java.time.Duration.ofHours(6)));
        if (!start.isBefore(end)) {
            throw new Exceptions.BadRequest("'from' must be before 'to'");
        }

        List<TelemetryResponses.ZoneHistoryPoint> points = metricRepository
                .findWindow(zone.getId(), start, end, PageRequest.of(0, properties.maxHistoryWindows()))
                .stream()
                .map(m -> new TelemetryResponses.ZoneHistoryPoint(
                        m.getWindowStart(), m.getOccupancyRatio(), m.getAverageSpeedKph(),
                        m.getAqi(), m.getRiskScore(),
                        m.getActiveIncidents() == null ? 0 : m.getActiveIncidents(),
                        m.getSampleCount()))
                .toList();

        return new TelemetryResponses.ZoneHistory(
                zone.getUid().toString(), zone.getCode(), start, end, points.size(), points);
    }

    /**
     * A city's history as one series, for the charts on the dashboard.
     *
     * <p>The per-zone endpoint above cannot answer a city-level question without
     * the caller fetching every zone and folding the results itself — twenty
     * requests to draw one sparkline, and the aggregation rule reimplemented in
     * the browser where it would drift from the one the snapshot uses.
     *
     * <p>That rule is the important part: averages are taken only across zones
     * that reported in each window. A window nobody reported in is absent from
     * the series rather than present as zero, so a chart drawn from this cannot
     * show a gap in the feed as a quiet hour.
     */
    @Transactional(readOnly = true)
    public TelemetryResponses.CityHistory cityHistory(String slug, Instant from, Instant to) {
        City city = cityRepository.findBySlugAndDeletedAtIsNull(slug)
                .orElseThrow(() -> new Exceptions.NotFound("City", slug));

        Instant end = Optional.ofNullable(to).orElseGet(Instant::now);
        Instant start = Optional.ofNullable(from)
                .orElseGet(() -> end.minus(java.time.Duration.ofHours(6)));
        if (!start.isBefore(end)) {
            throw new Exceptions.BadRequest("'from' must be before 'to'");
        }

        List<Zone> zones = zoneRepository.findByCity(city.getId(), true);
        if (zones.isEmpty()) {
            return new TelemetryResponses.CityHistory(
                    city.getUid().toString(), city.getSlug(), start, end, 0,
                    CURATED_WINDOW_MINUTES, 0, List.of());
        }

        List<Long> zoneIds = zones.stream().map(Zone::getId).toList();
        int bucketMinutes = bucketFor(start, end, properties.maxHistoryWindows());
        List<TelemetryResponses.CityHistoryPoint> points = metricRepository
                .findCityBuckets(zoneIds, start, end,
                        bucketMinutes * 60L, properties.maxHistoryWindows())
                .stream()
                .map(row -> new TelemetryResponses.CityHistoryPoint(
                        row.getWindowStart(),
                        scaled(row.getOccupancyRatio(), 4),
                        scaled(row.getAverageSpeedKph(), 2),
                        // AVG over an integer column comes back as a double; a
                        // fractional AQI is not a reading anyone quotes.
                        row.getAqi() == null ? null : (int) Math.round(row.getAqi()),
                        scaled(row.getRiskScore(), 2),
                        row.getVehicleCount(),
                        row.getActiveIncidents() == null ? null : row.getActiveIncidents().intValue(),
                        row.getReportingZones() == null ? 0 : row.getReportingZones().intValue()))
                .toList();

        return new TelemetryResponses.CityHistory(
                city.getUid().toString(), city.getSlug(), start, end,
                points.size(), bucketMinutes, zones.size(), points);
    }

    /** Curated windows are five minutes wide; see the Spark job's tumbling window. */
    private static final int CURATED_WINDOW_MINUTES = 5;

    /**
     * The narrowest bucket that fits the requested range inside the cap.
     *
     * <p>A month at the curated width is 8,640 points against a 500-point limit.
     * Returning the first 500 would answer "the last 30 days" with the first 42
     * hours of it, labelled as a month — a truncation nothing about the chart
     * would reveal. Widening the bucket instead keeps the whole range and says
     * in the response how wide each point is, so the caller can label its axis
     * for what it received.
     *
     * <p>The steps are ordinary reporting intervals rather than whatever
     * arithmetic produces, because an axis ticked every 23 minutes is not one
     * anybody reads.
     */
    static int bucketFor(Instant from, Instant to, int maxPoints) {
        long minutes = java.time.Duration.between(from, to).toMinutes();
        for (int bucket : new int[]{CURATED_WINDOW_MINUTES, 15, 30, 60, 180, 360, 720, 1440}) {
            if (minutes / bucket <= maxPoints) {
                return bucket;
            }
        }
        return 1440;
    }

    /** Rounds an aggregate to the precision the column is stored at. */
    private static BigDecimal scaled(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, java.math.RoundingMode.HALF_UP);
    }
}
