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

        List<TelemetryResponses.ZoneCondition> conditions = zones.stream()
                .map(zone -> toCondition(zone, latest.get(zone.getId())))
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

    private TelemetryResponses.ZoneCondition toCondition(Zone zone, ZoneMetric metric) {
        if (metric == null) {
            // Every metric null, hasData false. The client renders "no data"
            // rather than a zero-valued tile.
            return new TelemetryResponses.ZoneCondition(
                    zone.getUid().toString(), zone.getCode(), zone.getName(),
                    zone.getZoneType().name(),
                    zone.getCenterLatitude(), zone.getCenterLongitude(),
                    null, null,
                    null, null, null, null,
                    null, null,
                    null, null, null,
                    0, 0,
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
                metric.getAqi(), metric.getAqiCategory(),
                metric.getTemperatureC(), metric.getPrecipitationMmH(), metric.getWeatherCondition(),
                metric.getActiveIncidents() == null ? 0 : metric.getActiveIncidents(),
                metric.getActiveEvents() == null ? 0 : metric.getActiveEvents(),
                metric.getRiskScore(), metric.getRiskLevel(),
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
                reporting.stream().mapToInt(TelemetryResponses.ZoneCondition::activeIncidents).sum(),
                reporting.stream().mapToInt(TelemetryResponses.ZoneCondition::activeEvents).sum(),
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
}
