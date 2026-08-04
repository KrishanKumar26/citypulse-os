package com.citypulse.security.config;

import com.citypulse.common.config.SecurityProperties;
import com.citypulse.security.filter.JwtAuthenticationFilter;
import com.citypulse.security.filter.RateLimitFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * HTTP security configuration.
 *
 * <p>URL rules here are defence in depth. The authoritative authorization check
 * is {@code @PreAuthorize} at the service layer, which is what the security tests
 * assert (docs/SECURITY.md §3) — a controller added without a service-layer check
 * must not become reachable just because a URL pattern happens to permit it.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Cost 12: roughly 250 ms per hash on current hardware. High enough to make
     * offline cracking expensive, low enough that login latency stays acceptable
     * and the endpoint is not itself a denial-of-service vector.
     */
    private static final int BCRYPT_STRENGTH = 12;

    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1/auth/signup",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/verify-email",
            "/api/v1/meta/**",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            // Not actually public: the SSE stream authenticates with a
            // single-use ticket redeemed in the controller, because the browser's
            // EventSource API cannot send an Authorization header and putting a
            // JWT in the query string would leak it into access logs, history and
            // Referer headers. Excluded from the bearer chain, not from
            // authentication — see StreamTicketService.
            "/api/v1/live/by-slug/*/stream"
    };

    private final SecurityProperties properties;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(SecurityProperties properties,
                          JwtAuthenticationFilter jwtAuthenticationFilter,
                          RateLimitFilter rateLimitFilter,
                          RestAuthenticationEntryPoint authenticationEntryPoint,
                          RestAccessDeniedHandler accessDeniedHandler) {
        this.properties = properties;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // No cookie-based authentication is used, so there is no CSRF vector
                // to protect. Disabled explicitly and documented rather than left
                // to a default (docs/SECURITY.md §4).
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .headers(headers -> headers
                        .contentTypeOptions(opts -> {
                        })
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                        .ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        // This is a JSON API: it serves no scripts, styles, or frames.
                        // The policy denies everything so a reflected-content bug
                        // cannot execute anything.
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        // Prometheus scraping is network-restricted in deployment,
                        // not publicly readable.
                        .requestMatchers("/actuator/**").hasAuthority("system:manage")
                        .anyRequest().authenticated())
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        SecurityProperties.Cors cors = properties.cors();

        if (cors.allowedOrigins().stream().anyMatch(origin -> origin.contains("*"))) {
            // A wildcard cannot be combined with credentials, and silently
            // downgrading would remove the protection without anyone noticing.
            throw new IllegalStateException(
                    "citypulse.security.cors.allowed-origins must list exact origins; wildcards are not permitted");
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(cors.allowedOrigins());
        configuration.setAllowedMethods(cors.allowedMethods());
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id"));
        configuration.setExposedHeaders(List.of("X-Request-Id", "X-RateLimit-Limit",
                "X-RateLimit-Remaining", "X-RateLimit-Reset", "Retry-After"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(cors.maxAge().toSeconds());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    /*
     * Both filters are Spring beans, which Boot would otherwise also register
     * directly with the servlet container — running them twice per request. These
     * registrations disable the container-level copy; the security chain keeps
     * the ordered one.
     */

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> disableJwtFilterAutoRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> disableRateLimitFilterAutoRegistration(
            RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
