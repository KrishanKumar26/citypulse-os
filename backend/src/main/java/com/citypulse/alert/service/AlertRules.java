package com.citypulse.alert.service;

import com.citypulse.alert.domain.AlertSeverity;
import com.citypulse.alert.domain.AlertType;
import com.citypulse.telemetry.domain.ConditionLevel;
import com.citypulse.telemetry.domain.ZoneMetric;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The rules that produce Phase 4's first automatic alerts.
 *
 * <p>Thresholds here are the *alerting* thresholds, and they are deliberately
 * stricter than the display bands in {@code common/transforms.py}. A zone
 * showing HIGH on the map is worth colouring; it is not necessarily worth
 * interrupting someone about. Alerting at the display threshold is how an alert
 * feed becomes noise, and a noisy feed gets muted — which costs more than having
 * no alerts at all.
 *
 * <p>Each rule also requires a minimum sample count. A window built from two
 * readings can cross any threshold by chance, and an alert raised on it is a
 * false positive that teaches operators to distrust the rest.
 */
public final class AlertRules {

    private AlertRules() {
    }

    /** Below this, a window is too thin to alert on. */
    static final int MIN_SAMPLES = 3;

    private static boolean tooThin(ZoneMetric metric) {
        return metric.getSampleCount() == null || metric.getSampleCount() < MIN_SAMPLES;
    }

    // ---------------------------------------------------------------------
    // Congestion
    // ---------------------------------------------------------------------

    @Component
    public static class SevereCongestion implements AlertRule {

        /**
         * Fires above capacity, not at the HIGH band (0.80).
         *
         * <p>1.0 is the point where demand exceeds what the road network is rated
         * to carry — a qualitative change, not just a worse number, and the one
         * an operator can still do something about by diverting traffic.
         */
        static final BigDecimal THRESHOLD = new BigDecimal("1.00");

        @Override
        public String code() {
            return "SEVERE_CONGESTION";
        }

        @Override
        public AlertType type() {
            return AlertType.CRITICAL;
        }

        @Override
        public Optional<Finding> evaluate(ZoneMetric metric) {
            BigDecimal occupancy = metric.getOccupancyRatio();
            if (occupancy == null || tooThin(metric) || occupancy.compareTo(THRESHOLD) <= 0) {
                return Optional.empty();
            }

            // Well past capacity is a different situation from just over it.
            AlertSeverity severity = occupancy.compareTo(new BigDecimal("1.40")) > 0
                    ? AlertSeverity.CRITICAL
                    : AlertSeverity.HIGH;

            String speed = metric.getAverageSpeedKph() == null
                    ? "unknown"
                    : metric.getAverageSpeedKph().stripTrailingZeros().toPlainString() + " km/h";

            return Optional.of(new Finding(
                    severity,
                    "Severe congestion",
                    "Traffic is at %s%% of rated road capacity with average speed %s."
                            .formatted(occupancy.multiply(BigDecimal.valueOf(100)).setScale(0,
                                    java.math.RoundingMode.HALF_UP), speed),
                    "occupancy_ratio",
                    occupancy,
                    THRESHOLD,
                    "Divert through-traffic to adjacent corridors and review signal timing."
            ));
        }
    }

    // ---------------------------------------------------------------------
    // Air quality
    // ---------------------------------------------------------------------

    @Component
    public static class HazardousAirQuality implements AlertRule {

        /**
         * 300 is the CPCB boundary into VERY_POOR, where health advisories apply
         * to the general population rather than only sensitive groups. Below it
         * the number is worth showing, not worth paging about.
         */
        static final BigDecimal THRESHOLD = new BigDecimal("300");

        @Override
        public String code() {
            return "HAZARDOUS_AIR_QUALITY";
        }

        @Override
        public AlertType type() {
            return AlertType.WARNING;
        }

        @Override
        public Optional<Finding> evaluate(ZoneMetric metric) {
            Integer aqi = metric.getAqi();
            if (aqi == null || tooThin(metric)) {
                return Optional.empty();
            }
            BigDecimal observed = BigDecimal.valueOf(aqi);
            if (observed.compareTo(THRESHOLD) <= 0) {
                return Optional.empty();
            }

            AlertSeverity severity = aqi > 400 ? AlertSeverity.CRITICAL : AlertSeverity.HIGH;
            return Optional.of(new Finding(
                    severity,
                    "Hazardous air quality",
                    "AQI is %d (%s), above the advisory threshold of %s."
                            .formatted(aqi, metric.getAqiCategory(), THRESHOLD.toPlainString()),
                    "aqi",
                    observed,
                    THRESHOLD,
                    "Issue a public health advisory and restrict non-essential heavy vehicles."
            ));
        }
    }

    // ---------------------------------------------------------------------
    // Incidents
    // ---------------------------------------------------------------------

    @Component
    public static class MultipleIncidents implements AlertRule {

        /**
         * Three concurrent incidents in one zone.
         *
         * <p>One or two are routine. Three at once usually means a common cause —
         * weather, a closure pushing traffic somewhere it does not fit — which is
         * the situation worth a human looking at, and the correlation the
         * platform exists to surface.
         */
        static final BigDecimal THRESHOLD = new BigDecimal("3");

        @Override
        public String code() {
            return "MULTIPLE_INCIDENTS";
        }

        @Override
        public AlertType type() {
            return AlertType.WARNING;
        }

        @Override
        public Optional<Finding> evaluate(ZoneMetric metric) {
            Short incidents = metric.getActiveIncidents();
            if (incidents == null) {
                return Optional.empty();
            }
            BigDecimal observed = BigDecimal.valueOf(incidents);
            if (observed.compareTo(THRESHOLD) < 0) {
                return Optional.empty();
            }

            return Optional.of(new Finding(
                    incidents >= 5 ? AlertSeverity.HIGH : AlertSeverity.MEDIUM,
                    "Multiple concurrent incidents",
                    "%d incidents are open in this zone at once, which usually indicates a common cause."
                            .formatted(incidents),
                    "active_incidents",
                    observed,
                    THRESHOLD,
                    "Check for a shared cause — weather, a closure or an event — before dispatching separately."
            ));
        }
    }

    // ---------------------------------------------------------------------
    // Composite risk
    // ---------------------------------------------------------------------

    @Component
    public static class CriticalCompositeRisk implements AlertRule {

        /**
         * The composite score crossing into CRITICAL.
         *
         * <p>This is the rule that catches what the single-metric rules miss: a
         * zone where nothing individually breaches a threshold but congestion,
         * air quality, incidents and rain together add up to a bad situation.
         * That combination is the platform's central claim (PRD §12), so it earns
         * its own alert rather than being left implicit.
         */
        static final BigDecimal THRESHOLD = new BigDecimal("75");

        @Override
        public String code() {
            return "CRITICAL_COMPOSITE_RISK";
        }

        @Override
        public AlertType type() {
            return AlertType.CRITICAL;
        }

        @Override
        public Optional<Finding> evaluate(ZoneMetric metric) {
            BigDecimal risk = metric.getRiskScore();
            if (risk == null || tooThin(metric) || risk.compareTo(THRESHOLD) <= 0) {
                return Optional.empty();
            }
            if (metric.risk() != ConditionLevel.CRITICAL) {
                // Score and band are both stored; if they disagree the data is
                // suspect and raising on it would propagate the inconsistency.
                return Optional.empty();
            }

            return Optional.of(new Finding(
                    AlertSeverity.CRITICAL,
                    "Critical composite risk",
                    "Combined conditions score %s of 100 — congestion, air quality, incidents and weather together."
                            .formatted(risk.stripTrailingZeros().toPlainString()),
                    "risk_score",
                    risk,
                    THRESHOLD,
                    "Treat as a compound event: coordinate traffic, transport and public messaging together."
            ));
        }
    }
}
