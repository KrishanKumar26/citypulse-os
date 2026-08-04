package com.citypulse.common.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * General application settings bound from {@code citypulse.app.*}.
 *
 * @param name        product name used in API metadata and generated links
 * @param frontendUrl base URL of the web client, used to build verification and
 *                    password-reset links. Configured rather than derived from the
 *                    request, because deriving it from a {@code Host} header would
 *                    let an attacker point a reset link at their own domain
 * @param demoMode    when true the platform is serving synthetic data and the UI
 *                    labels it as such (PRD §42)
 */
@ConfigurationProperties(prefix = "citypulse.app")
@Validated
public record AppProperties(
        @NotBlank String name,
        @NotBlank String frontendUrl,
        boolean demoMode
) {

    public String buildLink(String path, String token) {
        String base = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
        return "%s%s?token=%s".formatted(base, path, token);
    }
}
