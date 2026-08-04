package com.citypulse.simulation.service;

import com.citypulse.simulation.dto.ScenarioRequests;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Computes counterfactual conditions from a real observed baseline (PRD §14).
 *
 * <h2>What this is, and what it is not</h2>
 *
 * <p>This is a <em>stated model</em>, not a prediction. It answers "given these
 * relationships, what would follow from this change" — not "this is what will
 * happen". Every assumption is written down below and unit tested, because a
 * simulator whose reasoning cannot be inspected is a machine for producing
 * confident-looking numbers.
 *
 * <p>Nothing here invents physics for the occasion. Speed, congestion bands and
 * risk come from {@link CityPhysics}, which is the same set of relationships the
 * pipeline uses to read real data. The engine changes the <em>inputs</em> —
 * demand and capacity — and lets the existing curves do the rest. That is what
 * makes a simulated risk score comparable to an observed one.
 *
 * <h2>The chain</h2>
 *
 * <pre>
 *   baseline occupancy (observed)
 *     → implied demand           = occupancy x capacity
 *     → adjusted demand          x event, transit, volume multipliers
 *     → adjusted capacity        x rain, closure factors
 *     → new occupancy            = adjusted demand / adjusted capacity
 *     → speed, congestion, risk  via CityPhysics
 * </pre>
 *
 * <p>Demand and capacity are moved separately on purpose. Rain and a road
 * closure both raise occupancy, but they are different situations: one adds
 * vehicles, the other removes road. Collapsing them into a single "congestion
 * multiplier" would produce the same number for both and make the recommended
 * actions wrong.
 *
 * <h2>Assumptions, stated</h2>
 *
 * <ol>
 *   <li><b>Baseline occupancy reflects real demand.</b> Implied demand is
 *       recovered by multiplying observed occupancy by rated capacity. This
 *       assumes the observed reading was not itself already distorted by an
 *       unrecorded closure.</li>
 *   <li><b>Event attendance converts to vehicles at a fixed rate</b>
 *       ({@link #VEHICLES_PER_ATTENDEE}), spread over the arrival window. Real
 *       modal split varies by city and event; this is a single figure and is
 *       stated as such rather than dressed up as calibrated.</li>
 *   <li><b>Spillover reaches adjacent zones at a fixed fraction</b>
 *       ({@link #SPILLOVER_FRACTION}). Adjacency is by distance, not by the road
 *       network — the platform holds no road graph, so this is a proximity
 *       heuristic and results are marked SPILLOVER to say so.</li>
 *   <li><b>Displaced transit riders become car traffic</b> at
 *       {@link #TRANSIT_TO_CAR_RATE}. Some would not travel at all; this
 *       over-states road impact, which is the safer direction for a warning
 *       tool.</li>
 *   <li><b>Weather is applied city-wide.</b> Rain in one zone and not another is
 *       not modelled.</li>
 *   <li><b>No feedback and no time evolution.</b> The engine computes one
 *       steady state. Drivers rerouting away from congestion, or a queue
 *       clearing over an hour, are not modelled — so results describe the moment
 *       of impact rather than what settles afterwards.</li>
 * </ol>
 */
@Component
public class ScenarioEngine {

    /** Bumped whenever an assumption changes, and stored on every simulation. */
    public static final String VERSION = "v1";

    /**
     * Cars generated per event attendee.
     *
     * <p>Assumes roughly two people per arriving vehicle and that a third of
     * attendees arrive by other means. A single number for every event type and
     * city — stated plainly rather than presented as calibrated.
     */
    static final double VEHICLES_PER_ATTENDEE = 0.33;

    /** Hours over which an event's arrivals are spread. */
    static final double EVENT_ARRIVAL_HOURS = 2.0;

    /**
     * Share of a zone's added demand that appears in each nearby zone.
     *
     * <p>Traffic bound for a busy place fills the approaches to it. Adjacency
     * here is by straight-line distance because the platform has no road graph,
     * which makes this the engine's least defensible step — hence the
     * SPILLOVER label on affected rows.
     */
    static final double SPILLOVER_FRACTION = 0.25;

    /** Zones within this radius of the event zone receive spillover. */
    static final double SPILLOVER_RADIUS_KM = 6.0;

    /** Fraction of displaced transit riders who drive instead. */
    static final double TRANSIT_TO_CAR_RATE = 0.45;

    /**
     * Transit share of total trips, used to size a disruption's road impact.
     *
     * <p>Indian metros run high transit shares; 0.35 is a mid-range figure for
     * the seeded cities and is an input to the model, not a measurement.
     */
    static final double TRANSIT_MODE_SHARE = 0.35;

    /**
     * One zone's observed starting point.
     *
     * @param ratedCapacityVph rated hourly capacity; the denominator occupancy
     *                         is expressed against
     */
    public record Baseline(
            String zoneCode,
            String zoneName,
            double latitude,
            double longitude,
            double ratedCapacityVph,
            Double occupancyRatio,
            Double speedKph,
            Integer vehicleCount,
            Integer aqi,
            int activeIncidents,
            Double precipitationMmH,
            Double riskScore
    ) {
    }

    /** One zone's counterfactual outcome. */
    public record Outcome(
            String zoneCode,
            Baseline baseline,
            double simulatedOccupancy,
            double simulatedSpeedKph,
            Integer simulatedVehicleCount,
            Double simulatedRiskScore,
            String simulatedCongestion,
            double delayChangeMinutes,
            double parkingChangePct,
            double crowdChangePct,
            String impactSource
    ) {
        public double occupancyChangePct() {
            if (baseline.occupancyRatio() == null || baseline.occupancyRatio() == 0.0) {
                return 0.0;
            }
            return (simulatedOccupancy / baseline.occupancyRatio() - 1.0) * 100.0;
        }
    }

    /**
     * Runs a scenario against observed baselines.
     *
     * <p>Zones with no baseline reading are skipped rather than defaulted. A
     * simulation built on an assumed starting point would produce a confident
     * before/after where the "before" was invented.
     */
    public List<Outcome> run(List<Baseline> baselines, ScenarioRequests.RunScenario scenario) {
        List<Outcome> outcomes = new ArrayList<>();

        Baseline eventZone = scenario.event() == null ? null
                : baselines.stream()
                        .filter(b -> b.zoneCode().equals(scenario.event().zoneCode()))
                        .findFirst().orElse(null);

        Set<String> closedZones = scenario.infrastructure() == null
                || scenario.infrastructure().closedRoadZoneCodes() == null
                ? Set.of()
                : Set.copyOf(scenario.infrastructure().closedRoadZoneCodes());

        Set<String> volumeZones = scenario.traffic() == null || scenario.traffic().zoneCodes() == null
                ? Set.of()
                : Set.copyOf(scenario.traffic().zoneCodes());

        for (Baseline baseline : baselines) {
            if (baseline.occupancyRatio() == null) {
                continue;
            }

            // Recover the demand the observed occupancy implies.
            double demandVph = baseline.occupancyRatio() * baseline.ratedCapacityVph();
            double capacityVph = baseline.ratedCapacityVph();
            String impactSource = "CITYWIDE";

            // --- Weather: reduces capacity, city-wide ---
            Double rain = baseline.precipitationMmH();
            if (scenario.weather() != null && scenario.weather().rainIntensityMmH() != null) {
                rain = scenario.weather().rainIntensityMmH();
                capacityVph *= CityPhysics.rainCapacityFactor(rain);
            }

            // --- Event: adds demand to its zone, less to nearby zones ---
            if (eventZone != null && scenario.event().expectedAttendance() != null) {
                double addedVehicles = scenario.event().expectedAttendance()
                        * VEHICLES_PER_ATTENDEE / EVENT_ARRIVAL_HOURS;

                if (baseline.zoneCode().equals(eventZone.zoneCode())) {
                    demandVph += addedVehicles;
                    impactSource = "DIRECT";
                } else if (distanceKm(baseline, eventZone) <= SPILLOVER_RADIUS_KM) {
                    demandVph += addedVehicles * SPILLOVER_FRACTION;
                    impactSource = "SPILLOVER";
                }
            }

            // --- Infrastructure: removes capacity, and transit adds demand ---
            if (scenario.infrastructure() != null) {
                var infra = scenario.infrastructure();
                if (closedZones.contains(baseline.zoneCode()) && infra.capacityReductionPct() != null) {
                    capacityVph *= 1.0 - infra.capacityReductionPct() / 100.0;
                    impactSource = "DIRECT";
                }
                if (infra.transitDisruptionPct() != null && infra.transitDisruptionPct() > 0) {
                    // Riders left without transit who choose to drive instead.
                    double displaced = demandVph * TRANSIT_MODE_SHARE
                            * (infra.transitDisruptionPct() / 100.0) * TRANSIT_TO_CAR_RATE;
                    demandVph += displaced;
                }
            }

            // --- Traffic: a direct change in vehicles ---
            if (scenario.traffic() != null && scenario.traffic().volumeChangePct() != null) {
                boolean applies = volumeZones.isEmpty() || volumeZones.contains(baseline.zoneCode());
                if (applies) {
                    demandVph *= 1.0 + scenario.traffic().volumeChangePct() / 100.0;
                    if (!volumeZones.isEmpty()) {
                        impactSource = "DIRECT";
                    }
                }
            }

            // Capacity can be reduced but never to zero: a closed road diverts
            // traffic, it does not divide by zero.
            capacityVph = Math.max(baseline.ratedCapacityVph() * 0.05, capacityVph);

            double simulatedOccupancy = demandVph / capacityVph;
            double simulatedSpeed = CityPhysics.speedFromOccupancy(simulatedOccupancy);
            Double simulatedRisk = CityPhysics.riskScore(
                    simulatedOccupancy, baseline.aqi(), baseline.activeIncidents(), rain);

            outcomes.add(new Outcome(
                    baseline.zoneCode(),
                    baseline,
                    round(simulatedOccupancy, 4),
                    round(simulatedSpeed, 2),
                    baseline.vehicleCount() == null ? null
                            : (int) Math.round(baseline.vehicleCount()
                                    * (simulatedOccupancy / baseline.occupancyRatio())),
                    simulatedRisk,
                    CityPhysics.congestionLevel(simulatedOccupancy),
                    round(delayMinutes(baseline, simulatedSpeed), 2),
                    round(parkingChangePct(baseline, simulatedOccupancy), 2),
                    round(crowdChangePct(baseline, simulatedOccupancy), 2),
                    impactSource));
        }

        return outcomes;
    }

    /**
     * Extra minutes on a representative trip through the zone.
     *
     * <p>Assumes a 5 km journey — a plausible cross-zone trip for the seeded
     * cities. The absolute figure depends on that choice; the *change* between
     * baseline and scenario does not, which is what the UI reports.
     */
    static final double REPRESENTATIVE_TRIP_KM = 5.0;

    private double delayMinutes(Baseline baseline, double simulatedSpeed) {
        if (baseline.speedKph() == null || baseline.speedKph() <= 0 || simulatedSpeed <= 0) {
            return 0.0;
        }
        double before = REPRESENTATIVE_TRIP_KM / baseline.speedKph() * 60.0;
        double after = REPRESENTATIVE_TRIP_KM / simulatedSpeed * 60.0;
        return after - before;
    }

    /**
     * Change in parking availability.
     *
     * <p>Modelled as proportional to the change in vehicles present, which
     * assumes a fixed share of arrivals park rather than passing through. The
     * platform has no parking sensors, so this is an inference from traffic and
     * is labelled as an impact rather than a measurement.
     */
    private double parkingChangePct(Baseline baseline, double simulatedOccupancy) {
        if (baseline.occupancyRatio() == null || baseline.occupancyRatio() == 0.0) {
            return 0.0;
        }
        double vehicleRatio = simulatedOccupancy / baseline.occupancyRatio();
        // More vehicles means less availability, hence the negation.
        return -(vehicleRatio - 1.0) * 100.0;
    }

    /**
     * Change in crowd intensity.
     *
     * <p>Derived from vehicle demand because the platform has no crowd sensor
     * (see docs/ML.md §1). Reported as a modelled impact, never as a reading.
     * The relationship is damped: people arrive by other means too, so crowd
     * does not track traffic one for one.
     */
    static final double CROWD_TO_TRAFFIC_ELASTICITY = 0.7;

    private double crowdChangePct(Baseline baseline, double simulatedOccupancy) {
        if (baseline.occupancyRatio() == null || baseline.occupancyRatio() == 0.0) {
            return 0.0;
        }
        double trafficChange = (simulatedOccupancy / baseline.occupancyRatio() - 1.0) * 100.0;
        return trafficChange * CROWD_TO_TRAFFIC_ELASTICITY;
    }

    /** Great-circle distance, adequate at city scale. */
    static double distanceKm(Baseline a, Baseline b) {
        double earthRadiusKm = 6371.0;
        double dLat = Math.toRadians(b.latitude() - a.latitude());
        double dLon = Math.toRadians(b.longitude() - a.longitude());
        double lat1 = Math.toRadians(a.latitude());
        double lat2 = Math.toRadians(b.latitude());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.sin(dLon / 2) * Math.sin(dLon / 2) * Math.cos(lat1) * Math.cos(lat2);
        return 2 * earthRadiusKm * Math.asin(Math.sqrt(h));
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }
}
