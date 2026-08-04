package com.citypulse.simulation.service;

import com.citypulse.simulation.dto.SimulationResponses.Recommendation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Actions a simulated outcome suggests (PRD §14).
 *
 * <p>Every recommendation names the zone and the reason that produced it. A
 * suggestion with no stated cause is advice the reader has to take on faith,
 * which PRD §15 rules out — and unlike a metric, advice acted on has real
 * consequences.
 *
 * <p>These are deliberately derived from thresholds on the outcome rather than
 * generated freely. The platform is not in a position to reason about a
 * particular city's operations; it can say "this zone crosses capacity, and
 * these are the standard responses to that". Anything more specific would be
 * inventing operational knowledge it does not have.
 */
final class Recommendations {

    private Recommendations() {
    }

    /** Above rated capacity: the point where the situation changes in kind. */
    private static final double OVER_CAPACITY = 1.0;

    /** A large enough jump that the zone's usual handling will not absorb it. */
    private static final double MATERIAL_INCREASE_PCT = 25.0;

    /** Composite risk entering the CRITICAL band. */
    private static final double CRITICAL_RISK = 75.0;

    static List<Recommendation> forOutcomes(List<ScenarioEngine.Outcome> outcomes) {
        List<Recommendation> recommendations = new ArrayList<>();

        List<ScenarioEngine.Outcome> overCapacity = outcomes.stream()
                .filter(o -> o.simulatedOccupancy() > OVER_CAPACITY)
                .sorted(Comparator.comparingDouble(ScenarioEngine.Outcome::simulatedOccupancy).reversed())
                .toList();

        for (ScenarioEngine.Outcome outcome : overCapacity) {
            recommendations.add(new Recommendation(
                    "Divert through-traffic away from " + outcome.baseline().zoneName(),
                    "Predicted load reaches %.0f%% of rated capacity, above what the road network carries."
                            .formatted(outcome.simulatedOccupancy() * 100),
                    outcome.zoneCode(),
                    outcome.simulatedOccupancy() > 1.4 ? "HIGH" : "MEDIUM"));
        }

        outcomes.stream()
                .filter(o -> o.simulatedRiskScore() != null && o.simulatedRiskScore() >= CRITICAL_RISK)
                .filter(o -> o.baseline().riskScore() == null || o.baseline().riskScore() < CRITICAL_RISK)
                .forEach(outcome -> recommendations.add(new Recommendation(
                        "Pre-position response resources in " + outcome.baseline().zoneName(),
                        "Composite risk moves from %.0f to %.0f, crossing into the critical band."
                                .formatted(
                                        outcome.baseline().riskScore() == null ? 0.0 : outcome.baseline().riskScore(),
                                        outcome.simulatedRiskScore()),
                        outcome.zoneCode(),
                        "HIGH")));

        outcomes.stream()
                .filter(o -> o.delayChangeMinutes() >= 10.0)
                .max(Comparator.comparingDouble(ScenarioEngine.Outcome::delayChangeMinutes))
                .ifPresent(outcome -> recommendations.add(new Recommendation(
                        "Issue a public travel advisory",
                        "Journeys through %s take an estimated %.0f minutes longer."
                                .formatted(outcome.baseline().zoneName(), outcome.delayChangeMinutes()),
                        outcome.zoneCode(),
                        "MEDIUM")));

        outcomes.stream()
                .filter(o -> o.parkingChangePct() <= -MATERIAL_INCREASE_PCT)
                .max(Comparator.comparingDouble(o -> -o.parkingChangePct()))
                .ifPresent(outcome -> recommendations.add(new Recommendation(
                        "Open overflow parking near " + outcome.baseline().zoneName(),
                        "Availability falls an estimated %.0f%%. Note this is inferred from traffic — "
                                .formatted(Math.abs(outcome.parkingChangePct()))
                                + "the platform has no parking sensors.",
                        outcome.zoneCode(),
                        "LOW")));

        // Spillover-only impacts get their own note, because the engine inferred
        // them from proximity rather than from the road network — a weaker basis
        // than a stated closure, and the reader should know which they are acting on.
        boolean spilloverOnly = outcomes.stream()
                .anyMatch(o -> "SPILLOVER".equals(o.impactSource()) && o.occupancyChangePct() > MATERIAL_INCREASE_PCT);
        if (spilloverOnly) {
            recommendations.add(new Recommendation(
                    "Verify approach routes before acting on spillover estimates",
                    "Neighbouring-zone effects are inferred from straight-line proximity, not from "
                    + "the road network, which the platform does not hold.",
                    null,
                    "LOW"));
        }

        return recommendations;
    }
}
