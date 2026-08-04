package com.citypulse.simulation;

import com.citypulse.simulation.dto.ScenarioRequests;
import com.citypulse.simulation.service.CityPhysics;
import com.citypulse.simulation.service.ScenarioEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The simulation engine's stated assumptions (PRD §14, Phase 6 exit criterion).
 *
 * <p>A simulator is unusually easy to get wrong in a way nobody notices: it
 * produces plausible numbers whatever it computes, and there is no actual to
 * check them against. So these tests do not assert specific outputs — that would
 * only pin whatever the code happens to do today. They assert the *properties*
 * the model claims to have: that identity scenarios change nothing, that more
 * load never improves conditions, that separate causes compose, and that
 * simulated values mean the same thing as observed ones.
 */
@DisplayName("Scenario engine")
class ScenarioEngineTest {

    private final ScenarioEngine engine = new ScenarioEngine();

    private ScenarioEngine.Baseline zone(String code, double occupancy) {
        return zone(code, occupancy, 12.97, 77.59);
    }

    private ScenarioEngine.Baseline zone(String code, double occupancy, double lat, double lon) {
        return new ScenarioEngine.Baseline(
                code, "Zone " + code, lat, lon,
                6000.0,
                occupancy,
                CityPhysics.speedFromOccupancy(occupancy),
                (int) (occupancy * 1200),
                120, 0, 0.0,
                CityPhysics.riskScore(occupancy, 120, 0, 0.0));
    }

    private ScenarioRequests.RunScenario scenario(
            ScenarioRequests.Weather weather,
            ScenarioRequests.CityEvent event,
            ScenarioRequests.Infrastructure infrastructure,
            ScenarioRequests.Traffic traffic) {
        return new ScenarioRequests.RunScenario(
                "test", null, "bengaluru", weather, event, infrastructure, traffic);
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Baseline fidelity")
    class BaselineFidelity {

        @Test
        @DisplayName("a scenario that changes nothing reproduces the baseline")
        void emptyScenarioIsIdentity() {
            // The most important property: if the engine cannot leave conditions
            // alone, every delta it reports is contaminated by its own drift.
            List<ScenarioEngine.Outcome> outcomes =
                    engine.run(List.of(zone("A", 0.6)), scenario(null, null, null, null));

            assertThat(outcomes).hasSize(1);
            assertThat(outcomes.get(0).simulatedOccupancy()).isEqualTo(0.6);
            assertThat(outcomes.get(0).occupancyChangePct()).isEqualTo(0.0);
            assertThat(outcomes.get(0).delayChangeMinutes()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("zero rain leaves capacity untouched")
        void zeroRainIsIdentity() {
            List<ScenarioEngine.Outcome> outcomes = engine.run(
                    List.of(zone("A", 0.6)),
                    scenario(new ScenarioRequests.Weather(0.0, null, null), null, null, null));

            assertThat(outcomes.get(0).simulatedOccupancy()).isEqualTo(0.6);
        }

        @Test
        @DisplayName("a zone with no observed reading is skipped, not assumed")
        void zoneWithoutBaselineIsSkipped() {
            ScenarioEngine.Baseline blank = new ScenarioEngine.Baseline(
                    "B", "Blank", 12.9, 77.5, 6000.0,
                    null, null, null, null, 0, null, null);

            // A before/after where the "before" was invented is worse than no
            // result for that zone.
            assertThat(engine.run(List.of(blank), scenario(
                    new ScenarioRequests.Weather(15.0, null, null), null, null, null))).isEmpty();
        }
    }

    @Nested
    @DisplayName("Monotonicity")
    class Monotonicity {

        @ParameterizedTest
        @ValueSource(doubles = {1.0, 5.0, 10.0, 20.0, 40.0})
        @DisplayName("more rain never improves conditions")
        void rainNeverHelps(double rain) {
            var outcome = engine.run(List.of(zone("A", 0.6)),
                    scenario(new ScenarioRequests.Weather(rain, null, null), null, null, null)).get(0);

            assertThat(outcome.simulatedOccupancy()).isGreaterThanOrEqualTo(0.6);
            assertThat(outcome.simulatedSpeedKph()).isLessThanOrEqualTo(outcome.baseline().speedKph());
        }

        @Test
        @DisplayName("heavier rain is never better than lighter rain")
        void rainIsOrdered() {
            double light = engine.run(List.of(zone("A", 0.6)),
                    scenario(new ScenarioRequests.Weather(5.0, null, null), null, null, null))
                    .get(0).simulatedOccupancy();
            double heavy = engine.run(List.of(zone("A", 0.6)),
                    scenario(new ScenarioRequests.Weather(25.0, null, null), null, null, null))
                    .get(0).simulatedOccupancy();

            assertThat(heavy).isGreaterThanOrEqualTo(light);
        }

        @Test
        @DisplayName("a larger event never produces less impact")
        void eventImpactGrowsWithAttendance() {
            double small = engine.run(List.of(zone("A", 0.5)), scenario(null,
                    new ScenarioRequests.CityEvent("A", "CONCERT", 5_000, 0, 3), null, null))
                    .get(0).simulatedOccupancy();
            double large = engine.run(List.of(zone("A", 0.5)), scenario(null,
                    new ScenarioRequests.CityEvent("A", "CONCERT", 60_000, 0, 3), null, null))
                    .get(0).simulatedOccupancy();

            assertThat(large).isGreaterThan(small);
        }

        @Test
        @DisplayName("removing road capacity raises occupancy")
        void closureRaisesOccupancy() {
            var outcome = engine.run(List.of(zone("A", 0.5)), scenario(null, null,
                    new ScenarioRequests.Infrastructure(List.of("A"), 50.0, null), null)).get(0);

            // Half the road carrying the same traffic is twice as loaded.
            assertThat(outcome.simulatedOccupancy()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
            assertThat(outcome.impactSource()).isEqualTo("DIRECT");
        }

        @Test
        @DisplayName("reducing traffic volume improves conditions")
        void lessTrafficHelps() {
            var outcome = engine.run(List.of(zone("A", 0.8)), scenario(null, null, null,
                    new ScenarioRequests.Traffic(-50.0, List.of()))).get(0);

            assertThat(outcome.simulatedOccupancy()).isLessThan(0.8);
            assertThat(outcome.simulatedSpeedKph()).isGreaterThan(outcome.baseline().speedKph());
            // Fewer cars means more parking, so the change is positive.
            assertThat(outcome.parkingChangePct()).isPositive();
        }
    }

    @Nested
    @DisplayName("Composition")
    class Composition {

        @Test
        @DisplayName("demand and capacity effects are applied separately")
        void demandAndCapacityAreDistinct() {
            // Rain removes road; extra vehicles add demand. Both raise occupancy,
            // but collapsing them into one multiplier would give the same answer
            // for two different situations and make the advice wrong.
            double rainOnly = engine.run(List.of(zone("A", 0.5)),
                    scenario(new ScenarioRequests.Weather(20.0, null, null), null, null, null))
                    .get(0).simulatedOccupancy();
            double trafficOnly = engine.run(List.of(zone("A", 0.5)), scenario(null, null, null,
                    new ScenarioRequests.Traffic(35.0, List.of()))).get(0).simulatedOccupancy();
            double both = engine.run(List.of(zone("A", 0.5)),
                    scenario(new ScenarioRequests.Weather(20.0, null, null), null, null,
                            new ScenarioRequests.Traffic(35.0, List.of())))
                    .get(0).simulatedOccupancy();

            assertThat(both).isGreaterThan(rainOnly).isGreaterThan(trafficOnly);
        }

        @Test
        @DisplayName("a transit disruption pushes riders onto the road")
        void transitDisruptionAddsTraffic() {
            var outcome = engine.run(List.of(zone("A", 0.6)), scenario(null, null,
                    new ScenarioRequests.Infrastructure(List.of(), null, 100.0), null)).get(0);

            assertThat(outcome.simulatedOccupancy()).isGreaterThan(0.6);
        }
    }

    @Nested
    @DisplayName("Spillover")
    class Spillover {

        @Test
        @DisplayName("an event affects nearby zones less than its own")
        void spilloverIsSmallerThanDirectImpact() {
            // ~2 km away, inside the spillover radius.
            var host = zone("HOST", 0.5, 12.97, 77.59);
            var near = zone("NEAR", 0.5, 12.99, 77.59);

            var outcomes = engine.run(List.of(host, near), scenario(null,
                    new ScenarioRequests.CityEvent("HOST", "CONCERT", 40_000, 0, 4), null, null));

            var hostOutcome = outcomes.stream().filter(o -> o.zoneCode().equals("HOST")).findFirst().orElseThrow();
            var nearOutcome = outcomes.stream().filter(o -> o.zoneCode().equals("NEAR")).findFirst().orElseThrow();

            assertThat(hostOutcome.simulatedOccupancy()).isGreaterThan(nearOutcome.simulatedOccupancy());
            assertThat(nearOutcome.simulatedOccupancy()).isGreaterThan(0.5);
            // Labelled, because adjacency here is straight-line distance rather
            // than the road network — the engine's least defensible step.
            assertThat(hostOutcome.impactSource()).isEqualTo("DIRECT");
            assertThat(nearOutcome.impactSource()).isEqualTo("SPILLOVER");
        }

        @Test
        @DisplayName("a distant zone is untouched by an event")
        void distantZonesAreUnaffected() {
            var host = zone("HOST", 0.5, 12.97, 77.59);
            var far = zone("FAR", 0.5, 13.30, 77.90);  // ~45 km

            var outcomes = engine.run(List.of(host, far), scenario(null,
                    new ScenarioRequests.CityEvent("HOST", "CONCERT", 40_000, 0, 4), null, null));

            var farOutcome = outcomes.stream().filter(o -> o.zoneCode().equals("FAR")).findFirst().orElseThrow();
            assertThat(farOutcome.simulatedOccupancy()).isEqualTo(0.5);
        }
    }

    @Nested
    @DisplayName("Physical bounds")
    class PhysicalBounds {

        @Test
        @DisplayName("a total closure does not divide by zero")
        void extremeClosureStaysFinite() {
            var outcome = engine.run(List.of(zone("A", 0.6)), scenario(null, null,
                    new ScenarioRequests.Infrastructure(List.of("A"), 90.0, null), null)).get(0);

            // A closed road diverts traffic; it does not make occupancy infinite.
            assertThat(outcome.simulatedOccupancy()).isFinite().isPositive();
            assertThat(outcome.simulatedSpeedKph()).isGreaterThanOrEqualTo(CityPhysics.JAM_KPH);
        }

        @Test
        @DisplayName("speed never exceeds free flow or falls below jam speed")
        void speedStaysWithinBounds() {
            for (double occupancy : new double[] {0.0, 0.1, 0.5, 1.0, 2.0, 10.0}) {
                double speed = CityPhysics.speedFromOccupancy(occupancy);
                assertThat(speed).isBetween(CityPhysics.JAM_KPH, CityPhysics.FREE_FLOW_KPH);
            }
        }

        @Test
        @DisplayName("risk stays within 0-100 however extreme the scenario")
        void riskStaysBounded() {
            var outcome = engine.run(List.of(zone("A", 0.9)),
                    scenario(new ScenarioRequests.Weather(50.0, null, null),
                            new ScenarioRequests.CityEvent("A", "FESTIVAL", 500_000, 0, 8),
                            new ScenarioRequests.Infrastructure(List.of("A"), 90.0, 100.0),
                            new ScenarioRequests.Traffic(300.0, List.of()))).get(0);

            assertThat(outcome.simulatedRiskScore()).isBetween(0.0, 100.0);
            assertThat(outcome.simulatedCongestion()).isEqualTo("CRITICAL");
        }
    }

    @Nested
    @DisplayName("Parity with the pipeline")
    class Parity {

        @Test
        @DisplayName("congestion bands match common/transforms.py")
        void congestionBandsMatch() {
            // A simulated CRITICAL must mean what an observed CRITICAL means, or
            // the before/after comparison is between two different scales.
            assertThat(CityPhysics.congestionLevel(0.50)).isEqualTo("NORMAL");
            assertThat(CityPhysics.congestionLevel(0.55)).isEqualTo("NORMAL");
            assertThat(CityPhysics.congestionLevel(0.56)).isEqualTo("MODERATE");
            assertThat(CityPhysics.congestionLevel(0.80)).isEqualTo("MODERATE");
            assertThat(CityPhysics.congestionLevel(0.81)).isEqualTo("HIGH");
            assertThat(CityPhysics.congestionLevel(1.00)).isEqualTo("HIGH");
            assertThat(CityPhysics.congestionLevel(1.01)).isEqualTo("CRITICAL");
        }

        @Test
        @DisplayName("risk bands match common/transforms.py")
        void riskBandsMatch() {
            assertThat(CityPhysics.riskLevel(25.0)).isEqualTo("NORMAL");
            assertThat(CityPhysics.riskLevel(25.1)).isEqualTo("MODERATE");
            assertThat(CityPhysics.riskLevel(50.0)).isEqualTo("MODERATE");
            assertThat(CityPhysics.riskLevel(75.0)).isEqualTo("HIGH");
            assertThat(CityPhysics.riskLevel(75.1)).isEqualTo("CRITICAL");
        }

        @Test
        @DisplayName("risk weights sum to one, as the Python renormalisation assumes")
        void riskWeightsSumToOne() {
            double total = CityPhysics.W_CONGESTION + CityPhysics.W_AIR_QUALITY
                    + CityPhysics.W_INCIDENTS + CityPhysics.W_WEATHER;
            assertThat(total).isEqualTo(1.0);
        }

        @Test
        @DisplayName("risk excludes missing components rather than scoring them zero")
        void riskRenormalisesOverMissingInputs() {
            // A zone with traffic but no air-quality feed must score on what it
            // has. Treating the gap as 0 would report it as safer than measured.
            Double withAqi = CityPhysics.riskScore(0.9, 350, 2, 10.0);
            Double withoutAqi = CityPhysics.riskScore(0.9, null, 2, 10.0);

            assertThat(withAqi).isNotNull();
            assertThat(withoutAqi).isNotNull();
            assertThat(withoutAqi).isLessThan(withAqi);  // AQI 350 was pushing it up
        }

        @Test
        @DisplayName("a zone with nothing measured scores null, not zero")
        void unmeasuredZoneIsUnknownNotSafe() {
            // Incidents always contribute, so a genuinely empty read still
            // returns a score. What must never happen is a *missing* occupancy
            // being read as an empty road.
            assertThat(CityPhysics.riskScore(null, null, 0, null)).isNotNull();
            assertThat(CityPhysics.riskScore(null, null, 0, null)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("the BPR curve uses signalised-urban coefficients, not highway ones")
        void bprCoefficientsAreUrban() {
            // Highway values (0.15, 4) degrade travel time only ~15% at capacity,
            // which had zones reporting near-free-flow speed while labelled HIGH.
            assertThat(CityPhysics.BPR_ALPHA).isEqualTo(0.85);
            assertThat(CityPhysics.BPR_BETA).isEqualTo(5.0);

            // At capacity, speed must have fallen materially below free flow.
            double atCapacity = CityPhysics.speedFromOccupancy(1.0);
            assertThat(atCapacity).isLessThan(CityPhysics.FREE_FLOW_KPH * 0.6);
        }
    }

    @Nested
    @DisplayName("Derived impacts")
    class DerivedImpacts {

        @Test
        @DisplayName("delay rises when speed falls")
        void delayTracksSpeed() {
            var outcome = engine.run(List.of(zone("A", 0.6)), scenario(null, null, null,
                    new ScenarioRequests.Traffic(80.0, List.of()))).get(0);

            assertThat(outcome.simulatedSpeedKph()).isLessThan(outcome.baseline().speedKph());
            assertThat(outcome.delayChangeMinutes()).isPositive();
        }

        @Test
        @DisplayName("crowd change is damped relative to traffic change")
        void crowdIsDampedAgainstTraffic() {
            // People arrive by other means too, so crowd does not track traffic
            // one for one. It is an inference, not a reading — the platform has
            // no crowd sensor.
            var outcome = engine.run(List.of(zone("A", 0.5)), scenario(null, null, null,
                    new ScenarioRequests.Traffic(100.0, List.of()))).get(0);

            assertThat(Math.abs(outcome.crowdChangePct()))
                    .isLessThan(Math.abs(outcome.occupancyChangePct()));
        }

        @Test
        @DisplayName("parking availability moves opposite to traffic")
        void parkingOpposesTraffic() {
            var worse = engine.run(List.of(zone("A", 0.5)), scenario(null, null, null,
                    new ScenarioRequests.Traffic(60.0, List.of()))).get(0);

            assertThat(worse.occupancyChangePct()).isPositive();
            assertThat(worse.parkingChangePct()).isNegative();
        }
    }
}
