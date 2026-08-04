package com.citypulse.audit.domain;

/**
 * The closed set of auditable actions. An enum rather than a free-text column so
 * audit queries and alerting rules cannot be broken by an inconsistent string.
 */
public enum AuditAction {
    // Authentication
    SIGNUP,
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    LOGOUT,
    TOKEN_REFRESH,
    /** A consumed refresh token was presented again — treated as token theft. */
    TOKEN_REUSE_DETECTED,
    ACCOUNT_LOCKED,

    // Credentials
    PASSWORD_CHANGE,
    PASSWORD_RESET_REQUEST,
    PASSWORD_RESET_COMPLETE,
    EMAIL_VERIFICATION,

    // Access control
    ROLE_ASSIGNED,
    ROLE_REMOVED,
    USER_CREATED,
    USER_UPDATED,
    USER_SUSPENDED,
    USER_REACTIVATED,

    // Platform administration
    CITY_CREATED,
    CITY_UPDATED,
    CITY_DELETED,
    ZONE_CREATED,
    ZONE_UPDATED,
    ZONE_DELETED,
    // Alerts. Acknowledging or resolving is an operator decision about a
    // city condition, so it has to be attributable (PRD §30).
    ALERT_ACKNOWLEDGED,
    ALERT_INVESTIGATING,
    ALERT_RESOLVED,

    API_KEY_CREATED,
    API_KEY_REVOKED,
    DATA_SOURCE_UPDATED
}
