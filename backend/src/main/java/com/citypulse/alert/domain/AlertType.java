package com.citypulse.alert.domain;

/**
 * Alert categories from PRD §17.
 *
 * <p>These describe what an alert is *about*, not how urgent it is — urgency is
 * {@link AlertSeverity}. Keeping them separate means a DATA_QUALITY alert can be
 * CRITICAL and a CRITICAL-category city condition can be MEDIUM, which a single
 * combined scale could not express.
 */
public enum AlertType {

    /** A city condition requiring immediate attention. */
    CRITICAL,

    /** A city condition trending toward trouble. */
    WARNING,

    /** Noteworthy but not actionable. */
    INFORMATIONAL,

    /** The platform itself: a service down, a pipeline stalled. */
    SYSTEM,

    /** Ingestion problems — rejection spikes, feeds gone silent. */
    DATA_QUALITY,

    /** Authentication and authorisation events worth surfacing. */
    SECURITY
}
