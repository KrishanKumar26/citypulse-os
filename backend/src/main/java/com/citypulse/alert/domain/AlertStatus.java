package com.citypulse.alert.domain;

/**
 * Alert lifecycle from PRD §17.
 *
 * <p>{@code RESOLVED} is the only terminal state, and it is what releases the
 * unique index on {@code dedupe_key} — so a condition that recurs after being
 * resolved raises a fresh alert rather than silently reusing the closed one.
 */
public enum AlertStatus {

    NEW,
    ACKNOWLEDGED,
    INVESTIGATING,
    RESOLVED;

    public boolean isOpen() {
        return this != RESOLVED;
    }

    /**
     * Whether a transition is allowed.
     *
     * <p>An alert may not move backwards out of RESOLVED: reopening would make
     * the resolution timestamp a lie, and the recurrence deserves its own record
     * with its own raised-at. Everything else is permitted, because an operator
     * may legitimately go straight from NEW to RESOLVED on a false alarm.
     */
    public boolean canTransitionTo(AlertStatus next) {
        if (this == next) {
            return false;
        }
        return this != RESOLVED;
    }
}
