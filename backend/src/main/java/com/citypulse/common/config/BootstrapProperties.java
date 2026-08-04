package com.citypulse.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional first-administrator bootstrap, bound from {@code citypulse.bootstrap.*}.
 *
 * <p>All fields default to empty. When they are left unset nothing is created:
 * the platform ships with no built-in administrator account and no default
 * credential to forget about (docs/SECURITY.md §1).
 */
@ConfigurationProperties(prefix = "citypulse.bootstrap")
public record BootstrapProperties(
        String adminEmail,
        String adminPassword,
        String adminName
) {

    public boolean isConfigured() {
        return adminEmail != null && !adminEmail.isBlank()
                && adminPassword != null && !adminPassword.isBlank();
    }
}
