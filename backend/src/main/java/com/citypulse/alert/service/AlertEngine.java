package com.citypulse.alert.service;

import com.citypulse.common.time.Timestamps;
import com.citypulse.alert.domain.Alert;
import com.citypulse.alert.domain.AlertStatus;
import com.citypulse.alert.repository.AlertRepository;
import com.citypulse.geo.domain.Zone;
import com.citypulse.geo.repository.ZoneRepository;
import com.citypulse.telemetry.config.TelemetryProperties;
import com.citypulse.telemetry.domain.ZoneMetric;
import com.citypulse.telemetry.repository.ZoneMetricRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Evaluates alert rules against the newest curated window for every zone
 * (PRD §17, Phase 4's "first automatic alerts").
 *
 * <p>Runs on a schedule rather than as part of ingestion. Alerting is a
 * different concern from loading, with a different failure tolerance: a rule
 * that throws must not be able to fail a batch of telemetry, and a pipeline
 * outage should still leave the last alerts standing rather than silently
 * stopping evaluation.
 */
@Service
public class AlertEngine {

    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);

    /**
     * Granularity of the dedupe key's time component.
     *
     * <p>An hour is the point of the whole mechanism: a zone congested from 08:00
     * to 09:00 produces one alert, not twelve five-minute ones. Making this
     * shorter would reintroduce the flood; making it longer would hide a
     * genuinely new episode later in the day behind an old alert.
     */
    private static final Duration DEDUPE_BUCKET = Duration.ofHours(1);

    private final ZoneRepository zoneRepository;
    private final ZoneMetricRepository metricRepository;
    private final AlertRepository alertRepository;
    private final List<AlertRule> rules;
    private final TelemetryProperties properties;

    public AlertEngine(ZoneRepository zoneRepository,
                       ZoneMetricRepository metricRepository,
                       AlertRepository alertRepository,
                       List<AlertRule> rules,
                       TelemetryProperties properties) {
        this.zoneRepository = zoneRepository;
        this.metricRepository = metricRepository;
        this.alertRepository = alertRepository;
        this.rules = rules;
        this.properties = properties;
        log.info("Alert engine initialised with {} rules: {}", rules.size(),
                rules.stream().map(AlertRule::code).sorted().toList());
    }

    /**
     * One evaluation cycle over every active zone.
     *
     * <p>Not annotated {@code @Transactional}: each zone commits on its own so a
     * failure on one does not roll back alerts correctly raised for the others.
     */
    @Scheduled(
            initialDelayString = "${citypulse.telemetry.stream-interval:PT5S}",
            fixedDelayString = "${citypulse.alerting.interval:PT30S}")
    public void evaluate() {
        Instant notBefore = Timestamps.now().minus(properties.maxAge());
        int raised = 0;
        int zonesChecked = 0;

        for (Zone zone : zoneRepository.findAllActive()) {
            Optional<ZoneMetric> latest = metricRepository.findLatestForZone(zone.getId(), notBefore);
            if (latest.isEmpty()) {
                continue;
            }
            zonesChecked++;
            raised += evaluateZone(zone, latest.get());
        }

        int resolved = autoResolveStale();
        if (raised > 0 || resolved > 0) {
            log.info("Alert cycle: {} zones checked, {} alerts raised, {} auto-resolved",
                    zonesChecked, raised, resolved);
        }
    }

    /**
     * Applies every rule to one zone's newest window.
     *
     * <p>A rule that throws is logged and skipped rather than aborting the cycle.
     * One badly-behaved rule taking down alerting entirely is a far worse outcome
     * than that rule producing nothing.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    int evaluateZone(Zone zone, ZoneMetric metric) {
        int raised = 0;
        for (AlertRule rule : rules) {
            try {
                Optional<AlertRule.Finding> finding = rule.evaluate(metric);
                if (finding.isPresent() && raise(rule, finding.get(), zone, metric)) {
                    raised++;
                }
            } catch (RuntimeException e) {
                log.error("Alert rule {} failed on zone {}: {}",
                        rule.code(), zone.getCode(), e.getMessage(), e);
            }
        }
        return raised;
    }

    /**
     * Raises an alert, or refreshes the open one for the same condition.
     *
     * @return true when a new alert was created
     */
    private boolean raise(AlertRule rule, AlertRule.Finding finding, Zone zone, ZoneMetric metric) {
        String key = dedupeKey(rule, zone, metric.getWindowStart());

        Optional<Alert> existing = alertRepository.findOpenByDedupeKey(key);
        if (existing.isPresent()) {
            // Same condition, still true: move the alert forward to the window
            // that re-confirmed it rather than raising a second one.
            Alert alert = existing.get();
            alert.setObservedValue(finding.observedValue());
            alert.setZoneMetricWindowStart(metric.getWindowStart());
            alert.setDescription(finding.description());
            alert.setUpdatedAt(Timestamps.now());
            alertRepository.save(alert);
            return false;
        }

        Alert alert = new Alert();
        alert.setAlertType(rule.type());
        alert.setSeverity(finding.severity());
        alert.setStatus(AlertStatus.NEW);
        alert.setTitle("%s — %s".formatted(finding.title(), zone.getName()));
        alert.setDescription(finding.description());
        alert.setZone(zone);
        alert.setCity(zone.getCity());
        alert.setRuleCode(rule.code());
        alert.setMetricName(finding.metricName());
        alert.setObservedValue(finding.observedValue());
        alert.setThresholdValue(finding.thresholdValue());
        alert.setZoneMetricWindowStart(metric.getWindowStart());
        alert.setRecommendedAction(finding.recommendedAction());
        alert.setDedupeKey(key);
        alert.setDemoData(metric.isDemoData());

        try {
            alertRepository.save(alert);
            return true;
        } catch (DataIntegrityViolationException e) {
            // Another instance raised the same alert between the check and the
            // insert. The partial unique index is the authority, and losing this
            // race is the correct outcome — not an error worth surfacing.
            log.debug("Alert {} already raised concurrently for zone {}", rule.code(), zone.getCode());
            return false;
        }
    }

    /**
     * Builds the suppression identity: rule, zone, and the hour the window falls in.
     */
    private String dedupeKey(AlertRule rule, Zone zone, Instant windowStart) {
        Instant bucket = windowStart.truncatedTo(ChronoUnit.SECONDS)
                .minusSeconds(windowStart.getEpochSecond() % DEDUPE_BUCKET.toSeconds());
        return "%s:%s:%s".formatted(rule.code(), zone.getCode(), bucket);
    }

    /**
     * Closes open alerts whose evidence has aged out.
     *
     * <p>A congestion alert whose zone stopped reporting hours ago is not still
     * true; leaving it open would fill the Alert Center with conditions nobody
     * can act on, which is the same fatigue problem deduplication solves from the
     * other direction.
     */
    @Transactional
    public int autoResolveStale() {
        Instant cutoff = Timestamps.now().minus(properties.maxAge());
        List<Alert> stale = alertRepository.findStaleOpen(cutoff);
        Instant now = Timestamps.now();

        for (Alert alert : stale) {
            alert.setStatus(AlertStatus.RESOLVED);
            alert.setResolvedAt(now);
            alert.setResolutionNote(
                    "Automatically resolved: no telemetry for this zone since "
                    + alert.getZoneMetricWindowStart());
            alert.setUpdatedAt(now);
        }
        alertRepository.saveAll(stale);
        return stale.size();
    }
}
