package com.citypulse.intervention.service;

import com.citypulse.common.exception.Exceptions;
import com.citypulse.common.time.Timestamps;
import com.citypulse.geo.domain.City;
import com.citypulse.geo.domain.Zone;
import com.citypulse.geo.repository.CityRepository;
import com.citypulse.geo.repository.ZoneRepository;
import com.citypulse.intervention.dto.InterventionRequests;
import com.citypulse.security.CurrentUser;
import com.citypulse.user.domain.User;
import com.citypulse.user.repository.UserRepository;
import com.citypulse.intervention.domain.Intervention;
import com.citypulse.intervention.dto.InterventionResponses;
import com.citypulse.intervention.repository.InterventionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Interventions, and what the city did afterwards (PRD §16).
 *
 * <p>The measurement is deliberately conservative, because this is the easiest
 * place in the product to manufacture a success story.
 *
 * <p>Three rules it follows:
 *
 * <p><b>A raw before/after difference is not an impact.</b> Congestion falls in
 * the evening whether or not anyone acted, so the difference alone credits the
 * intervention with the sunset. Every metric is compared against what this zone
 * normally reads at those hours of the week, and the figure reported as the
 * result is the movement *beyond* what the baseline already predicted.
 *
 * <p><b>Absent is not zero.</b> If either side of the comparison has no curated
 * windows, the intervention is reported as unmeasurable rather than as having
 * no effect. An action taken during a feed outage would otherwise score however
 * the missing data happened to average.
 *
 * <p><b>Even the excess is not proof.</b> It is a measured coincidence between a
 * stated action and a departure from normal. Nothing here establishes that the
 * action caused it, and the response never says it does.
 */
@Service
public class InterventionService {

    private final InterventionRepository repository;
    private final CityRepository cityRepository;
    private final ZoneRepository zoneRepository;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;

    public InterventionService(InterventionRepository repository,
                               CityRepository cityRepository,
                               ZoneRepository zoneRepository,
                               UserRepository userRepository,
                               CurrentUser currentUser) {
        this.repository = repository;
        this.cityRepository = cityRepository;
        this.zoneRepository = zoneRepository;
        this.userRepository = userRepository;
        this.currentUser = currentUser;
    }

    /**
     * Records that someone took an action.
     *
     * <p>Attributed to the caller, always. An intervention with no author is an
     * assertion nobody owns, and everything the impact figures are later read
     * against rests on a person having been there and said so.
     */
    @Transactional
    public InterventionResponses.InterventionDetail record(InterventionRequests.Create request) {
        City city = cityRepository.findBySlugAndDeletedAtIsNull(request.citySlug())
                .orElseThrow(() -> new Exceptions.NotFound("City", request.citySlug()));

        User actor = userRepository.findByUidAndDeletedAtIsNull(currentUser.require().userUid())
                .orElseThrow(() -> new Exceptions.NotFound("User", currentUser.require().userUid()));

        Intervention intervention = new Intervention();
        intervention.setTitle(request.title());
        intervention.setDescription(request.description());
        intervention.setActionType(request.actionType());
        intervention.setCity(city);

        if (request.zoneId() != null) {
            Zone zone = zoneRepository.findByUidAndDeletedAtIsNull(request.zoneId())
                    .orElseThrow(() -> new Exceptions.NotFound("Zone", request.zoneId()));
            if (!zone.getCity().getId().equals(city.getId())) {
                // Silently accepting it would attach the action to a baseline
                // from a different city and measure it against the wrong normal.
                throw new Exceptions.BadRequest("Zone does not belong to " + city.getSlug());
            }
            intervention.setZone(zone);
        }

        // Rejected rather than clamped. A start in the future is a data-entry
        // error, and quietly moving it to now would record a time nobody chose.
        if (request.startedAt().isAfter(Timestamps.now().plus(Duration.ofMinutes(5)))) {
            throw new Exceptions.BadRequest("'startedAt' cannot be in the future");
        }
        intervention.setStartedAt(request.startedAt());
        intervention.setEndedAt(request.endedAt());
        intervention.setStatus(request.endedAt() == null ? "ACTIVE" : "COMPLETED");
        intervention.setRecordedBy(actor);
        intervention.setComparisonMinutes(
                request.comparisonMinutes() == null ? 60 : request.comparisonMinutes());
        intervention.setNotes(request.notes());
        // Inherited from the city: an action taken against generated telemetry
        // is a rehearsal, and must not be readable later as a real one.
        intervention.setDemoData(city.isDemoData());

        return toDetail(repository.save(intervention));
    }

    @Transactional(readOnly = true)
    public List<InterventionResponses.InterventionDetail> listForCity(String slug) {
        City city = cityRepository.findBySlugAndDeletedAtIsNull(slug)
                .orElseThrow(() -> new Exceptions.NotFound("City", slug));
        return repository.findForCity(city.getId()).stream().map(this::toDetail).toList();
    }

    private InterventionResponses.InterventionDetail toDetail(Intervention i) {
        return new InterventionResponses.InterventionDetail(
                i.getUid().toString(),
                i.getTitle(),
                i.getDescription(),
                i.getActionType(),
                i.getZone() == null ? null : i.getZone().getUid().toString(),
                i.getZone() == null ? null : i.getZone().getName(),
                i.getCity().getSlug(),
                i.getStartedAt(),
                i.getEndedAt(),
                i.getStatus(),
                i.getRecordedBy().getEmail(),
                i.getComparisonMinutes(),
                i.getNotes(),
                // A city-wide action has no zone baseline, so there is nothing
                // to measure it against. Returning null says that plainly
                // rather than inventing a city-level normal that was never
                // learned.
                i.getZone() == null ? null : measure(i),
                i.isDemoData());
    }

    /** Before and after, against what the hours involved normally look like. */
    private InterventionResponses.Impact measure(Intervention i) {
        Duration span = Duration.ofMinutes(i.getComparisonMinutes());
        Instant start = i.getStartedAt();
        Instant afterEnd = i.getEndedAt() == null
                ? Timestamps.now()
                : i.getEndedAt().plus(span);
        Instant afterStart = i.getEndedAt() == null ? start : i.getEndedAt();

        var before = repository.meanForZone(i.getZone().getId(), start.minus(span), start);
        var after = repository.meanForZone(i.getZone().getId(), afterStart, afterEnd);

        long windowsBefore = before == null || before.getWindows() == null ? 0 : before.getWindows();
        long windowsAfter = after == null || after.getWindows() == null ? 0 : after.getWindows();

        if (windowsBefore == 0 || windowsAfter == 0) {
            // Unmeasurable, not ineffective. An action taken during a feed
            // outage would otherwise score however the missing data averaged.
            return new InterventionResponses.Impact(
                    windowsBefore, windowsAfter, false,
                    windowsBefore == 0
                            ? "No curated windows before the stated start"
                            : "No curated windows after it yet",
                    i.getEndedAt() == null, List.of());
        }

        Map<String, InterventionRepository.BaselineMean> baselines =
                repository.baselineForHours(i.getZone().getId(), hoursSpanned(start, afterEnd))
                        .stream()
                        .collect(Collectors.toMap(
                                InterventionRepository.BaselineMean::getMetric,
                                Function.identity(), (a, b) -> a));

        List<InterventionResponses.MetricImpact> metrics = new ArrayList<>();
        metrics.add(impactOf("occupancy_ratio", before.getOccupancy(), after.getOccupancy(), baselines));
        metrics.add(impactOf("average_speed_kph", before.getSpeed(), after.getSpeed(), baselines));
        metrics.add(impactOf("risk_score", before.getRisk(), after.getRisk(), baselines));

        return new InterventionResponses.Impact(
                windowsBefore, windowsAfter, true, null,
                // Still running: the window after it is still filling, so the
                // number will move. Saying so stops a provisional reading being
                // quoted as a result.
                i.getEndedAt() == null,
                metrics);
    }

    private InterventionResponses.MetricImpact impactOf(
            String metric, BigDecimal before, BigDecimal after,
            Map<String, InterventionRepository.BaselineMean> baselines) {

        BigDecimal changePct = percentChange(before, after);
        InterventionRepository.BaselineMean baseline = baselines.get(metric);

        BigDecimal excess = null;
        if (changePct != null && baseline != null && baseline.getMedian() != null
                && before != null && before.signum() != 0) {
            // How far the *before* reading already sat from normal, against how
            // far the *after* reading does. The difference between those two
            // gaps is the movement the baseline does not already explain.
            BigDecimal gapBefore = percentChange(baseline.getMedian(), before);
            BigDecimal gapAfter = percentChange(baseline.getMedian(), after);
            if (gapBefore != null && gapAfter != null) {
                excess = gapAfter.subtract(gapBefore).setScale(2, RoundingMode.HALF_UP);
            }
        }

        return new InterventionResponses.MetricImpact(
                metric,
                scaled(before), scaled(after), changePct,
                baseline == null ? null : scaled(baseline.getMedian()),
                excess,
                baseline == null || baseline.getSamples() == null ? 0 : baseline.getSamples());
    }

    private static BigDecimal percentChange(BigDecimal from, BigDecimal to) {
        if (from == null || to == null || from.signum() == 0) {
            // Undefined against a zero baseline, not infinite and not zero.
            return null;
        }
        return to.subtract(from)
                .divide(from.abs(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Hours of the week the comparison touches.
     *
     * <p>Baselines are keyed by hour of week, so an intervention spanning the
     * evening peak must be compared against the evening peak rather than
     * against a flat daily average that would flatter or punish it arbitrarily.
     */
    private static List<Integer> hoursSpanned(Instant from, Instant to) {
        List<Integer> hours = new ArrayList<>();
        Instant cursor = from.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        while (!cursor.isAfter(to) && hours.size() < 168) {
            var utc = cursor.atZone(ZoneOffset.UTC);
            hours.add((utc.getDayOfWeek().getValue() % 7) * 24 + utc.getHour());
            cursor = cursor.plus(Duration.ofHours(1));
        }
        return hours.isEmpty() ? List.of(0) : hours.stream().distinct().toList();
    }
}
