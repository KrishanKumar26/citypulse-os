package com.citypulse.user.domain;

/**
 * Permission name constants, mirroring the {@code permissions} table seeded in
 * migration {@code V2__seed_rbac.sql}.
 *
 * <p>These strings appear in {@code @PreAuthorize} expressions, which are not
 * compile-time checked. {@code RbacConsistencyIT} asserts that every constant
 * here exists in the database, so a typo fails the build rather than silently
 * denying or granting access.
 */
public final class Permissions {

    // Users, roles, and access control
    public static final String USER_READ = "user:read";
    public static final String USER_WRITE = "user:write";
    public static final String USER_MANAGE_ROLES = "user:manage_roles";
    public static final String ROLE_READ = "role:read";

    // Geography
    public static final String CITY_READ = "city:read";
    public static final String CITY_WRITE = "city:write";
    public static final String ZONE_READ = "zone:read";
    public static final String ZONE_WRITE = "zone:write";

    // Intelligence (endpoints arrive in later phases; permissions seeded now
    // so role definitions do not have to be migrated repeatedly)
    public static final String TELEMETRY_READ = "telemetry:read";
    public static final String FORECAST_READ = "forecast:read";
    public static final String ANOMALY_READ = "anomaly:read";
    public static final String ALERT_READ = "alert:read";
    public static final String ALERT_MANAGE = "alert:manage";
    public static final String SIMULATION_READ = "simulation:read";
    public static final String SIMULATION_CREATE = "simulation:create";
    public static final String ANALYTICS_READ = "analytics:read";
    public static final String ANALYTICS_EXPORT = "analytics:export";

    // Platform administration
    public static final String DATASOURCE_READ = "datasource:read";
    public static final String DATASOURCE_MANAGE = "datasource:manage";
    public static final String APIKEY_READ = "apikey:read";
    public static final String APIKEY_MANAGE = "apikey:manage";
    public static final String AUDIT_READ = "audit:read";
    public static final String SYSTEM_MANAGE = "system:manage";

    private Permissions() {
    }
}
