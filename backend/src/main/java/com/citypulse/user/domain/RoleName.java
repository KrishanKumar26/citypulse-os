package com.citypulse.user.domain;

/**
 * The seven system roles defined in PRD §5. Held as constants rather than an
 * enum column so roles remain data (addable without a schema change) while the
 * names the code depends on stay compile-time checked.
 */
public final class RoleName {

    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String ADMIN = "ADMIN";
    public static final String CITY_OPERATOR = "CITY_OPERATOR";
    public static final String ANALYST = "ANALYST";
    public static final String FLEET_MANAGER = "FLEET_MANAGER";
    public static final String DEVELOPER = "DEVELOPER";
    public static final String VIEWER = "VIEWER";

    /** Role assigned on self-service signup: read-only until an admin elevates it. */
    public static final String DEFAULT_SIGNUP_ROLE = VIEWER;

    private RoleName() {
    }
}
