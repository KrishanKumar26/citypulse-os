package com.citypulse.alert;

import com.citypulse.alert.domain.AlertSeverity;
import com.citypulse.alert.service.AlertRule;
import com.citypulse.alert.service.AlertRules;
import com.citypulse.telemetry.domain.ZoneMetric;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static com.citypulse.support.ZoneMetricFixture.aMetric;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules behind Phase 4's automatic alerts.
 *
 * <p>These are pure functions of one curated window, so they are tested without
 * Spring or a database — which matters because thresholds are exactly the kind
 * of thing that gets adjusted, and a slow suite is one nobody runs before
 * adjusting them.
 *
 * <p>Two properties get more attention than the happy path. First, that a rule
 * does *not* fire just below its threshold: alerting at the display band would
 * bury operators in notifications and a muted feed is worth less than no feed.
 * Second, that a thin sample never fires: a window built from two readings can
 * cross any threshold by chance, and false positives teach people to distrust
 * the real ones.
 */
@DisplayName("Alert rules")
class AlertRulesTest {

    @Nested
    @DisplayName("Severe congestion")
    class SevereCongestionTest {

        private final AlertRules.SevereCongestion rule = new AlertRules.SevereCongestion();

        @Test
        @DisplayName("fires above rated road capacity")
        void firesAboveCapacity() {
            Optional<AlertRule.Finding> finding = rule.evaluate(
                    aMetric().occupancy("1.20").speed("14.0").build());

            assertThat(finding).isPresent();
            assertThat(finding.get().metricName()).isEqualTo("occupancy_ratio");
            assertThat(finding.get().observedValue()).isEqualByComparingTo("1.20");
            assertThat(finding.get().thresholdValue()).isEqualByComparingTo("1.00");
            assertThat(finding.get().recommendedAction()).isNotBlank();
        }

        @Test
        @DisplayName("stays quiet at the HIGH display band, which is not an alerting condition")
        void doesNotFireAtDisplayBand() {
            // 0.90 renders HIGH on the map. Colouring a tile and interrupting a
            // person are different bars, and conflating them is how alert fatigue
            // starts.
            assertThat(rule.evaluate(aMetric().occupancy("0.90").build())).isEmpty();
        }

        @Test
        @DisplayName("does not fire exactly at the threshold")
        void thresholdIsExclusive() {
            assertThat(rule.evaluate(aMetric().occupancy("1.00").build())).isEmpty();
        }

        @Test
        @DisplayName("escalates to CRITICAL well past capacity")
        void escalatesWhenFarOverCapacity() {
            assertThat(rule.evaluate(aMetric().occupancy("1.10").build()).orElseThrow().severity())
                    .isEqualTo(AlertSeverity.HIGH);
            assertThat(rule.evaluate(aMetric().occupancy("1.60").build()).orElseThrow().severity())
                    .isEqualTo(AlertSeverity.CRITICAL);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 2})
        @DisplayName("never fires on a thin sample, however extreme the reading")
        void ignoresThinSamples(int samples) {
            assertThat(rule.evaluate(aMetric().occupancy("3.00").samples(samples).build())).isEmpty();
        }

        @Test
        @DisplayName("ignores a window with no traffic reading at all")
        void ignoresMissingMetric() {
            assertThat(rule.evaluate(aMetric().build())).isEmpty();
        }

        @Test
        @DisplayName("survives a null sample count without throwing")
        void toleratesNullSampleCount() {
            assertThat(rule.evaluate(aMetric().occupancy("2.00").samples(null).build())).isEmpty();
        }
    }

    @Nested
    @DisplayName("Hazardous air quality")
    class HazardousAirQualityTest {

        private final AlertRules.HazardousAirQuality rule = new AlertRules.HazardousAirQuality();

        @Test
        @DisplayName("fires above the public advisory boundary")
        void firesAboveAdvisory() {
            Optional<AlertRule.Finding> finding = rule.evaluate(
                    aMetric().aqi(340).aqiCategory("VERY_POOR").build());

            assertThat(finding).isPresent();
            assertThat(finding.get().severity()).isEqualTo(AlertSeverity.HIGH);
            assertThat(finding.get().observedValue()).isEqualByComparingTo("340");
        }

        @Test
        @DisplayName("stays quiet in the POOR band, where advisories apply only to sensitive groups")
        void doesNotFireBelowAdvisory() {
            assertThat(rule.evaluate(aMetric().aqi(280).aqiCategory("POOR").build())).isEmpty();
        }

        @Test
        @DisplayName("escalates to CRITICAL in the SEVERE band")
        void escalatesWhenSevere() {
            assertThat(rule.evaluate(aMetric().aqi(450).aqiCategory("SEVERE").build())
                    .orElseThrow().severity()).isEqualTo(AlertSeverity.CRITICAL);
        }

        @Test
        @DisplayName("ignores a window with no air quality reading")
        void ignoresMissingMetric() {
            assertThat(rule.evaluate(aMetric().occupancy("0.4").build())).isEmpty();
        }
    }

    @Nested
    @DisplayName("Multiple incidents")
    class MultipleIncidentsTest {

        private final AlertRules.MultipleIncidents rule = new AlertRules.MultipleIncidents();

        @Test
        @DisplayName("fires on three concurrent incidents, which usually share a cause")
        void firesOnThree() {
            Optional<AlertRule.Finding> finding = rule.evaluate(aMetric().incidents(3).build());

            assertThat(finding).isPresent();
            assertThat(finding.get().severity()).isEqualTo(AlertSeverity.MEDIUM);
            assertThat(finding.get().metricName()).isEqualTo("active_incidents");
        }

        @Test
        @DisplayName("treats one or two incidents as routine")
        void doesNotFireOnRoutineCounts() {
            assertThat(rule.evaluate(aMetric().incidents(0).build())).isEmpty();
            assertThat(rule.evaluate(aMetric().incidents(2).build())).isEmpty();
        }

        @Test
        @DisplayName("escalates when five or more are open at once")
        void escalatesOnMany() {
            assertThat(rule.evaluate(aMetric().incidents(5).build()).orElseThrow().severity())
                    .isEqualTo(AlertSeverity.HIGH);
        }

        @Test
        @DisplayName("fires regardless of sample count, because a count is not an average")
        void doesNotRequireASample() {
            // Unlike occupancy or AQI, an incident count is not sampled — three
            // incidents are three incidents whether the window saw one traffic
            // reading or sixty. Requiring a sample here would suppress real alerts.
            assertThat(rule.evaluate(aMetric().incidents(4).samples(1).build())).isPresent();
        }
    }

    @Nested
    @DisplayName("Critical composite risk")
    class CriticalCompositeRiskTest {

        private final AlertRules.CriticalCompositeRisk rule = new AlertRules.CriticalCompositeRisk();

        @Test
        @DisplayName("fires when combined conditions reach the critical band")
        void firesWhenCritical() {
            Optional<AlertRule.Finding> finding = rule.evaluate(
                    aMetric().risk("88.50", "CRITICAL").build());

            assertThat(finding).isPresent();
            assertThat(finding.get().severity()).isEqualTo(AlertSeverity.CRITICAL);
            assertThat(finding.get().metricName()).isEqualTo("risk_score");
        }

        @Test
        @DisplayName("does not fire in the HIGH band")
        void doesNotFireBelowCritical() {
            assertThat(rule.evaluate(aMetric().risk("70.00", "HIGH").build())).isEmpty();
        }

        @Test
        @DisplayName("refuses to fire when the score and its band disagree")
        void refusesInconsistentData() {
            // Both are written by the pipeline from the same input. If they
            // disagree the row is suspect, and raising an alert on it would
            // propagate the inconsistency into something an operator acts on.
            assertThat(rule.evaluate(aMetric().risk("90.00", "MODERATE").build())).isEmpty();
        }

        @Test
        @DisplayName("ignores a window with no risk score")
        void ignoresMissingMetric() {
            assertThat(rule.evaluate(aMetric().occupancy("0.5").build())).isEmpty();
        }
    }

    @Nested
    @DisplayName("Every rule")
    class UniversalProperties {

        private final java.util.List<AlertRule> rules = java.util.List.of(
                new AlertRules.SevereCongestion(),
                new AlertRules.HazardousAirQuality(),
                new AlertRules.MultipleIncidents(),
                new AlertRules.CriticalCompositeRisk());

        @Test
        @DisplayName("has a distinct, stable code — it is stored on every alert it raises")
        void codesAreDistinct() {
            assertThat(rules.stream().map(AlertRule::code).distinct().count())
                    .isEqualTo(rules.size());
            assertThat(rules).allSatisfy(rule -> assertThat(rule.code()).isNotBlank());
        }

        @Test
        @DisplayName("returns empty rather than throwing on an entirely empty window")
        void toleratesAnEmptyWindow() {
            // The engine catches exceptions per rule, but a rule that throws on
            // ordinary missing data would fill the log and hide real failures.
            ZoneMetric empty = aMetric().build();
            assertThat(rules).allSatisfy(rule -> assertThat(rule.evaluate(empty)).isEmpty());
        }

        @Test
        @DisplayName("reports the metric, observed value and threshold whenever it fires")
        void findingsAlwaysCarryProvenance() {
            // PRD §15: an alert the UI cannot explain is indistinguishable from
            // one the platform invented.
            ZoneMetric severe = aMetric()
                    .occupancy("1.80").speed("8.0").aqi(420).aqiCategory("SEVERE")
                    .incidents(6).risk("95.00", "CRITICAL").build();

            assertThat(rules).allSatisfy(rule -> {
                AlertRule.Finding finding = rule.evaluate(severe).orElseThrow(
                        () -> new AssertionError(rule.code() + " should fire on a severe window"));
                assertThat(finding.metricName()).isNotBlank();
                assertThat(finding.observedValue()).isNotNull();
                assertThat(finding.thresholdValue()).isNotNull();
                assertThat(finding.title()).isNotBlank();
                assertThat(finding.description()).isNotBlank();
                assertThat(finding.recommendedAction()).isNotBlank();
            });
        }
    }
}
