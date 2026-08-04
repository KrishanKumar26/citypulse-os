package com.citypulse.user.domain;

public enum UserStatus {
    /** Registered but email not yet confirmed. */
    PENDING_VERIFICATION,
    /** Able to authenticate and use the platform. */
    ACTIVE,
    /** Disabled by an administrator; authentication is refused. */
    SUSPENDED
}
