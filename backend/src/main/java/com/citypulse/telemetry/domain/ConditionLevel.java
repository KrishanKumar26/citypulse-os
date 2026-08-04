package com.citypulse.telemetry.domain;

/**
 * The four city-condition states from PRD §9.
 *
 * <p>One scale for congestion, air quality and composite risk alike, so a colour
 * means the same thing wherever it appears in the product. Separate scales per
 * metric would make a red tile ambiguous — the reader would have to remember
 * which dimension each one was measuring before knowing how alarmed to be.
 *
 * <p>The pipeline derives these (see {@code common/transforms.py}); the backend
 * only reads them. Deriving them again here would create a second definition
 * that could drift from the one the data was written with.
 */
public enum ConditionLevel {

    NORMAL,
    MODERATE,
    HIGH,
    CRITICAL;

    /**
     * Parses a stored value, tolerating nulls.
     *
     * <p>Returns {@code null} rather than a default for an absent reading:
     * "not measured" and "normal" are different facts, and collapsing them would
     * let a zone with no data render as a healthy one.
     */
    public static ConditionLevel fromNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return ConditionLevel.valueOf(value);
    }

    /** True for the states an operator is expected to act on. */
    public boolean isActionable() {
        return this == HIGH || this == CRITICAL;
    }
}
