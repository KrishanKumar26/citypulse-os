package com.citypulse.alert.service;

import com.citypulse.alert.domain.AlertSeverity;
import com.citypulse.alert.domain.AlertType;
import com.citypulse.telemetry.domain.ZoneMetric;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * One condition worth telling an operator about.
 *
 * <p>Rules are deliberately small, pure and independent: each looks at a single
 * curated window and decides whether it fires. No rule reads the database, and
 * none knows about any other, which is what makes them testable in isolation and
 * safe to add to.
 *
 * <p>Every rule must report the metric and threshold behind its decision. That
 * is not bookkeeping — an alert the UI cannot explain is indistinguishable from
 * one the platform invented, and PRD §15 rules that out.
 */
public interface AlertRule {

    /** Stable identifier, stored on the alert and used to build its dedupe key. */
    String code();

    AlertType type();

    /**
     * Evaluates one window.
     *
     * @return the alert to raise, or empty when the condition does not hold
     */
    Optional<Finding> evaluate(ZoneMetric metric);

    /**
     * What a fired rule produces.
     *
     * @param severity           how urgent
     * @param title              short summary for a list row
     * @param description        what was observed, in a sentence an operator can act on
     * @param metricName         the field that triggered it
     * @param observedValue      the value seen
     * @param thresholdValue     the value it crossed
     * @param recommendedAction  what to do about it (PRD §13, §15)
     */
    record Finding(
            AlertSeverity severity,
            String title,
            String description,
            String metricName,
            BigDecimal observedValue,
            BigDecimal thresholdValue,
            String recommendedAction
    ) {
    }
}
