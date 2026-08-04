package com.citypulse.audit.domain;

public enum AuditOutcome {
    SUCCESS,
    FAILURE,
    /** The action was refused by an authorization check. */
    DENIED
}
