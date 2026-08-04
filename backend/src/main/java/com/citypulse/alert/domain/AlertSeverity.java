package com.citypulse.alert.domain;

/** How urgent an alert is, independent of what it is about. */
public enum AlertSeverity {

    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    /** Ordering for "most severe first" listings. */
    public int rank() {
        return ordinal();
    }
}
