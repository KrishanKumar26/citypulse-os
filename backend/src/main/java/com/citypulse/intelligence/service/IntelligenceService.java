package com.citypulse.intelligence.service;

import com.citypulse.common.api.PageResponse;
import com.citypulse.common.exception.Exceptions;
import com.citypulse.geo.domain.City;
import com.citypulse.geo.repository.CityRepository;
import com.citypulse.intelligence.domain.Anomaly;
import com.citypulse.intelligence.domain.ConditionCorrelation;
import com.citypulse.intelligence.domain.SituationMemory;
import com.citypulse.intelligence.dto.IntelligenceResponses;
import com.citypulse.intelligence.repository.AnomalyRepository;
import com.citypulse.intelligence.repository.CorrelationRepository;
import com.citypulse.intelligence.repository.SituationMemoryRepository;
import com.citypulse.telemetry.config.TelemetryProperties;
import com.citypulse.telemetry.domain.ZoneMetric;
import com.citypulse.telemetry.repository.ZoneMetricRepository;
import com.citypulse.user.domain.Permissions;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Serves what the intelligence jobs produced (PRD §12, §13, §16).
 *
 * <p>Reads only. Baselines, anomalies, situations and correlations are computed
 * by the Python batch jobs, so anything served here was derived deliberately
 * from data and can be traced back to it.
 *
 * <p>The one thing this service does compute is the *current* situation
 * fingerprint, because that depends on conditions as of this request. It is
 * matched against the stored memory rather than reasoned about.
 */
@Service
public class IntelligenceService {

    /**
     * Comparable situations needed before a historical pattern is reported.
     *
     * <p>Below this the median is drawn from too few examples to mean anything,
     * and the response says so instead. PRD §15 requires the distinction between
     * "nothing happened" and "we cannot tell" to be visible.
     */
    static final int MIN_SITUATIONS_FOR_PATTERN = 5;

    /** How many examples to return alongside an aggregate. */
    private static final int EXAMPLE_LIMIT = 5;

    private final AnomalyRepository anomalyRepository;
    private final CorrelationRepository correlationRepository;
    private final SituationMemoryRepository memoryRepository;
    private final CityRepository cityRepository;
    private final ZoneMetricRepository metricRepository;
    private final TelemetryProperties telemetryProperties;
    private final EntityManager entityManager;

    public IntelligenceService(AnomalyRepository anomalyRepository,
                               CorrelationRepository correlationRepository,
                               SituationMemoryRepository memoryRepository,
                               CityRepository cityRepository,
                               ZoneMetricRepository metricRepository,
                               TelemetryProperties telemetryProperties,
                               EntityManager entityManager) {
        this.anomalyRepository = anomalyRepository;
        this.correlationRepository = correlationRepository;
        this.memoryRepository = memoryRepository;
        this.cityRepository = cityRepository;
        this.metricRepository = metricRepository;
        this.telemetryProperties = telemetryProperties;
        this.entityManager = entityManager;
    }

    // ------------------------------------------------------------------
    // Anomalies
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.ANOMALY_READ + "')")
    public PageResponse<IntelligenceResponses.AnomalyDetail> anomalies(
            String citySlug, String severity, Integer hours, Pageable pageable) {
        City city = requireCity(citySlug);
        // Always a concrete instant: a null bind cannot have its type inferred
        // by PostgreSQL, and "all history" is expressible as a far-past bound.
        Instant since = hours == null
                ? Instant.EPOCH
                : Instant.now().minus(hours, ChronoUnit.HOURS);

        var page = severity == null || severity.isBlank()
                ? anomalyRepository.findSince(city.getId(), since, pageable)
                : anomalyRepository.findSinceWithSeverity(city.getId(), since, severity, pageable);

        return PageResponse.from(page, this::toDetail);
    }

    private IntelligenceResponses.AnomalyDetail toDetail(Anomaly a) {
        return new IntelligenceResponses.AnomalyDetail(
                a.getUid().toString(),
                a.getZone().getUid().toString(), a.getZone().getCode(), a.getZone().getName(),
                a.getMetric(), a.getAnomalyType(), a.getSeverity(),
                a.getWindowStart(), a.getObservedValue(), a.getBaselineValue(),
                a.getDeviationScore(), a.getPercentChange(), a.getBaselineSamples(),
                a.getExplanation(), a.getDetectedAt(), a.isDemoData());
    }

    // ------------------------------------------------------------------
    // Correlations
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.ANALYTICS_READ + "')")
    public List<IntelligenceResponses.Correlation> correlations(String citySlug) {
        City city = requireCity(citySlug);
        return correlationRepository.findForCity(city.getId()).stream()
                .map(this::toCorrelation)
                .toList();
    }

    private IntelligenceResponses.Correlation toCorrelation(ConditionCorrelation c) {
        return new IntelligenceResponses.Correlation(
                c.getConditionA(), c.getConditionB(),
                Explanations.forCorrelation(c),
                c.getLift(), c.getConfidence(),
                c.getWindowsWithA(), c.getWindowsWithBoth(), c.getWindowsTotal(),
                // Stated explicitly in the payload so a client cannot present
                // co-occurrence as cause by omission.
                false);
    }

    // ------------------------------------------------------------------
    // City Memory
    // ------------------------------------------------------------------

    /**
     * Asks the memory what usually follows conditions like these (PRD §16).
     *
     * <p>Tries the exact fingerprint first and widens once if that is too rare.
     * If even the widened match is thin, it reports insufficient data rather
     * than a median over three examples.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.ANALYTICS_READ + "')")
    public IntelligenceResponses.MemoryRecall recall(String citySlug,
                                                     String rainBand,
                                                     String dayType,
                                                     String hourBand,
                                                     boolean hadEvent,
                                                     String incidentBand) {
        City city = requireCity(citySlug);

        List<SituationMemory> matches = memoryRepository.findSimilar(
                city.getId(), rainBand, dayType, hourBand, hadEvent, incidentBand);
        boolean relaxed = false;

        if (matches.size() < MIN_SITUATIONS_FOR_PATTERN) {
            matches = memoryRepository.findSimilarRelaxed(
                    city.getId(), rainBand, dayType, hourBand, hadEvent);
            relaxed = true;
        }

        if (matches.size() < MIN_SITUATIONS_FOR_PATTERN) {
            return new IntelligenceResponses.MemoryRecall(
                    rainBand, dayType, hourBand, hadEvent, incidentBand,
                    false,
                    Explanations.insufficientMemory(matches.size(), MIN_SITUATIONS_FOR_PATTERN,
                            rainBand, hourBand, hadEvent),
                    relaxed, matches.size(),
                    null, null, null,
                    Explanations.insufficientMemory(matches.size(), MIN_SITUATIONS_FOR_PATTERN,
                            rainBand, hourBand, hadEvent),
                    List.of());
        }

        BigDecimal medianOccupancy = median(matches, SituationMemory::getOccupancyChangePct);
        BigDecimal medianSpeed = median(matches, SituationMemory::getSpeedChangePct);
        BigDecimal medianRisk = median(matches, SituationMemory::getRiskChangePct);

        List<IntelligenceResponses.RecalledSituation> examples = matches.stream()
                .limit(EXAMPLE_LIMIT)
                .map(s -> new IntelligenceResponses.RecalledSituation(
                        s.getZone().getCode(), s.getZone().getName(), s.getOccurredAt(),
                        s.getOccupancyAtStart(), s.getPeakOccupancy(),
                        s.getOccupancyChangePct(), s.getSpeedChangePct(), s.getRiskChangePct(),
                        s.getOutcomeHorizonMinutes()))
                .toList();

        return new IntelligenceResponses.MemoryRecall(
                rainBand, dayType, hourBand, hadEvent, incidentBand,
                true, null, relaxed, matches.size(),
                medianOccupancy, medianSpeed, medianRisk,
                Explanations.forMemory(matches.size(), medianOccupancy, medianSpeed,
                        rainBand, hourBand, hadEvent, relaxed),
                examples);
    }

    /**
     * Median rather than mean.
     *
     * <p>A single extraordinary afternoon would drag a mean somewhere no
     * ordinary day goes, and the question the memory answers — "what usually
     * happens" — is about the typical case.
     */
    private BigDecimal median(List<SituationMemory> matches,
                              java.util.function.Function<SituationMemory, BigDecimal> field) {
        List<BigDecimal> values = matches.stream()
                .map(field)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
        if (values.isEmpty()) {
            return null;
        }
        int mid = values.size() / 2;
        BigDecimal result = values.size() % 2 == 1
                ? values.get(mid)
                : values.get(mid - 1).add(values.get(mid)).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        return result.setScale(2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------
    // Summary
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.ANALYTICS_READ + "')")
    public IntelligenceResponses.InsightsSummary summary(String citySlug) {
        City city = requireCity(citySlug);
        Instant since = Instant.now().minus(24, ChronoUnit.HOURS);

        long recent = anomalyRepository.countRecent(city.getId(), since);
        List<IntelligenceResponses.AnomalyDetail> top = anomalyRepository
                .findSince(city.getId(), since, PageRequest.of(0, 5))
                .map(this::toDetail).getContent();

        Integer buckets = (Integer) entityManager.createNativeQuery("""
                SELECT count(*)::int FROM zone_baselines b
                JOIN zones z ON z.id = b.zone_id
                WHERE z.city_id = :cityId
                """).setParameter("cityId", city.getId()).getSingleResult();

        return new IntelligenceResponses.InsightsSummary(
                city.getSlug(), (int) recent, top,
                correlations(citySlug),
                currentSituation(city).orElse(null),
                buckets == null ? 0 : buckets);
    }

    /**
     * Fingerprints the city's conditions right now and recalls against them.
     *
     * <p>Returns empty when no zone has a recent enough window: a fingerprint
     * built from stale readings would recall the wrong situations and present
     * them as relevant to now.
     */
    private Optional<IntelligenceResponses.MemoryRecall> currentSituation(City city) {
        Instant notBefore = Instant.now().minus(telemetryProperties.maxAge());
        List<ZoneMetric> latest = metricRepository.findLatestPerZoneInCity(city.getId(), notBefore);
        if (latest.isEmpty()) {
            return Optional.empty();
        }

        // The busiest reporting zone stands for the city's current situation. A
        // city-wide average would blur a storm over one district into nothing.
        ZoneMetric representative = latest.stream()
                .filter(m -> m.getOccupancyRatio() != null)
                .max(Comparator.comparing(ZoneMetric::getOccupancyRatio))
                .orElse(null);
        if (representative == null) {
            return Optional.empty();
        }

        var local = representative.getWindowStart().atZone(ZoneId.of(city.getTimezone()));
        double rain = representative.getPrecipitationMmH() == null
                ? 0.0 : representative.getPrecipitationMmH().doubleValue();
        int incidents = representative.getActiveIncidents() == null
                ? 0 : representative.getActiveIncidents();

        return Optional.of(recall(
                city.getSlug(),
                rainBand(rain),
                local.getDayOfWeek().getValue() >= 6 ? "WEEKEND" : "WEEKDAY",
                hourBand(local.getHour()),
                (representative.getActiveEvents() == null ? 0 : representative.getActiveEvents()) > 0,
                incidents == 0 ? "NONE" : incidents <= 2 ? "SOME" : "MANY"));
    }

    /** Bands mirroring intelligence/jobs.py, so a fingerprint built here matches one stored there. */
    static String rainBand(double mmH) {
        if (mmH <= 0.1) return "NONE";
        if (mmH < 2.5) return "LIGHT";
        if (mmH < 10.0) return "MODERATE";
        return "HEAVY";
    }

    static String hourBand(int hour) {
        if (hour < 6) return "OVERNIGHT";
        if (hour < 11) return "MORNING_PEAK";
        if (hour < 16) return "MIDDAY";
        if (hour < 21) return "EVENING_PEAK";
        return "EVENING";
    }

    private City requireCity(String slug) {
        return cityRepository.findBySlugAndDeletedAtIsNull(slug)
                .orElseThrow(() -> new Exceptions.NotFound("City", slug));
    }
}
